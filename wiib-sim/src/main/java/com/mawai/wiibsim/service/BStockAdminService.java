package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.BStockAdminDTO;
import com.mawai.wiibsim.dto.BStockCatalogSyncResult;
import com.mawai.wiibsim.dto.UpdateBStockAdminRequest;
import com.mawai.wiibsim.mapper.BStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 管理员维护影子股票展示身份与目录生命周期。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BStockAdminService {

    private static final Set<String> STATUSES = Set.of("CANDIDATE", "LISTED", "PAUSED", "RETIRED");

    private final BStockMapper bStockMapper;
    private final BStockService bStockService;
    private final CryptoOrderService cryptoOrderService;
    private final BStockAliasLlmService aliasLlmService;
    private final BStockCatalogSyncService syncService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    public IPage<BStockAdminDTO> page(String status, String keyword, int pageNum, int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        LambdaQueryWrapper<BStock> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) query.eq(BStock::getCatalogStatus, normalizeStatus(status));
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.trim();
            query.and(w -> w.like(BStock::getDisplayName, q)
                    .or().like(BStock::getDisplayCode, q)
                    .or().like(BStock::getName, q)
                    .or().like(BStock::getTicker, q)
                    .or().like(BStock::getSymbol, q));
        }
        query.orderByAsc(BStock::getSort).orderByAsc(BStock::getId);
        Page<BStock> page = new Page<>(safePage, safeSize);
        bStockMapper.selectPage(page, query);
        return page.convert(this::toDTO);
    }

    @Transactional(rollbackFor = Exception.class)
    public BStockAdminDTO update(Long id, UpdateBStockAdminRequest request) {
        BStock stock = requireStockForUpdate(id);
        boolean aliasEdited = request.getDisplayName() != null
                || request.getDisplayCode() != null
                || request.getDisplayLore() != null;
        if (request.getDisplayName() != null) stock.setDisplayName(requireText(request.getDisplayName(), "展示名", 64));
        if (request.getDisplayCode() != null) stock.setDisplayCode(requireCode(request.getDisplayCode()));
        if (request.getDisplayLore() != null) stock.setDisplayLore(requireOptionalText(request.getDisplayLore(), "世界观文案", 255));
        if (aliasEdited) {
            stock.setAliasSource("MANUAL");
            stock.setAliasLocked(true);
        }
        if (request.getSort() != null) stock.setSort(Math.max(0, request.getSort()));
        if (request.getCatalogStatus() != null) applyStatus(stock, request.getCatalogStatus());
        stock.setUpdatedAt(LocalDateTime.now());
        bStockMapper.updateById(stock);
        if (request.getCatalogStatus() != null && !"LISTED".equals(stock.getCatalogStatus())) {
            cancelPendingBuys(stock.getSymbol());
        }
        refreshAfterCommit();
        return toDTO(stock);
    }

    @Transactional(rollbackFor = Exception.class)
    public int batchStatus(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) throw new BizException(ErrorCode.PARAM_ERROR);
        String normalized = normalizeStatus(status);
        List<Long> normalizedIds = ids.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        if (normalizedIds.isEmpty()) throw new BizException(ErrorCode.PARAM_ERROR);
        int changed = 0;
        for (Long id : normalizedIds) {
            BStock stock = requireStockForUpdate(id);
            applyStatus(stock, normalized);
            stock.setUpdatedAt(LocalDateTime.now());
            bStockMapper.updateById(stock);
            if (!"LISTED".equals(stock.getCatalogStatus())) cancelPendingBuys(stock.getSymbol());
            changed++;
        }
        refreshAfterCommit();
        return changed;
    }

    public BStockAdminDTO regenerateAlias(Long id) {
        if (id == null) throw new BizException(ErrorCode.PARAM_ERROR);
        BStock source = bStockMapper.selectById(id);
        if (source == null) throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        AliasRevision sourceRevision = AliasRevision.from(source);

        // 网络调用必须在事务与 FOR UPDATE 之外，避免慢模型长时间占住目录行锁。
        BStockAliasGenerator.Alias alias = aliasLlmService.generate(source);
        BStockAdminDTO result = transactionTemplate.execute(status -> {
            BStock stock = requireStockForUpdate(id);
            if (!sourceRevision.equals(AliasRevision.from(stock))) {
                throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED.getCode(),
                        "生成期间影子身份已被修改，本次LLM结果未写入，请重试");
            }
            long conflicts = bStockMapper.selectCount(new LambdaQueryWrapper<BStock>()
                    .ne(BStock::getId, id)
                    .and(w -> w.eq(BStock::getDisplayName, alias.displayName())
                            .or().eq(BStock::getDisplayCode, alias.displayCode())));
            if (conflicts > 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "LLM生成的名称或代码与其他影子股票重复，原别名未修改，请重试");
            }
            stock.setDisplayName(alias.displayName());
            stock.setDisplayCode(alias.displayCode());
            stock.setDisplayLore(alias.displayLore());
            stock.setAliasSource("LLM");
            stock.setAliasVersion(alias.version());
            stock.setAliasLocked(true);
            stock.setUpdatedAt(LocalDateTime.now());
            bStockMapper.updateById(stock);
            refreshAfterCommit();
            return toDTO(stock);
        });
        if (result == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        return result;
    }

    private record AliasRevision(String displayName, String displayCode, String displayLore,
                                 String aliasSource, Integer aliasVersion, Boolean aliasLocked) {
        private static AliasRevision from(BStock stock) {
            return new AliasRevision(stock.getDisplayName(), stock.getDisplayCode(), stock.getDisplayLore(),
                    stock.getAliasSource(), stock.getAliasVersion(), stock.getAliasLocked());
        }
    }

    public BStockCatalogSyncResult syncNow() {
        return syncService.syncNow();
    }

    private void applyStatus(BStock stock, String value) {
        String status = normalizeStatus(value);
        stock.setCatalogStatus(status);
        // PAUSED 保持在市场和持仓分类里可见；RETIRED/CANDIDATE 只从目录隐藏，不删历史。
        stock.setEnabled("LISTED".equals(status) || "PAUSED".equals(status));
    }

    private void cancelPendingBuys(String symbol) {
        int cancelled = cryptoOrderService.cancelPendingBuys(symbol);
        if (cancelled > 0) {
            log.info("影子股票生命周期变化后已撤销待买单 symbol={} count={}", symbol, cancelled);
        }
    }

    private void refreshAfterCommit() {
        Runnable refresh = () -> {
            bStockService.invalidateCatalogCache();
            publishChange();
        };
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            refresh.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refresh.run();
            }
        });
    }

    private void publishChange() {
        try {
            redisTemplate.convertAndSend(BStockCatalogSyncService.CATALOG_CHANGED_CHANNEL, LocalDateTime.now().toString());
        } catch (Exception e) {
            // feed 仍会轮询数据库；通知失败不能反向污染已经提交的后台操作。
            log.warn("影子股票目录通知失败，将由 feed 轮询收敛: {}", e.getMessage());
        }
    }

    private BStock requireStockForUpdate(Long id) {
        if (id == null) throw new BizException(ErrorCode.PARAM_ERROR);
        BStock stock = bStockMapper.selectByIdForUpdate(id);
        if (stock == null) throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        return stock;
    }

    private String normalizeStatus(String value) {
        if (value == null) throw new BizException(ErrorCode.PARAM_ERROR);
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw new BizException(ErrorCode.PARAM_ERROR);
        return status;
    }

    private String requireText(String value, String field, int max) {
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.length() > max) {
            log.debug("{}长度无效", field);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        return normalized;
    }

    private String requireCode(String value) {
        String code = requireText(value, "展示代码", 16).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9._-]{1,16}")) throw new BizException(ErrorCode.PARAM_ERROR);
        return code;
    }

    private String requireOptionalText(String value, String field, int max) {
        String normalized = value.trim();
        if (normalized.length() > max) {
            log.debug("{}长度无效", field);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        return normalized;
    }

    private BStockAdminDTO toDTO(BStock stock) {
        BStockAdminDTO dto = new BStockAdminDTO();
        BeanUtils.copyProperties(stock, dto);
        dto.setBuyAllowed("LISTED".equals(stock.getCatalogStatus()) && "TRADING".equals(stock.getSourceStatus()));
        return dto;
    }
}
