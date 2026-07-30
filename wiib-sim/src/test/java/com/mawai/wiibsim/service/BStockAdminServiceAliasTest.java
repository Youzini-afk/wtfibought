package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.BStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BStockAdminServiceAliasTest {

    private final BStockMapper mapper = mock(BStockMapper.class);
    private final BStockAliasLlmService aliasLlmService = mock(BStockAliasLlmService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final BStockAdminService service = new BStockAdminService(
            mapper,
            mock(BStockService.class),
            mock(CryptoOrderService.class),
            aliasLlmService,
            mock(BStockCatalogSyncService.class),
            mock(StringRedisTemplate.class),
            transactionTemplate);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void executeTransactionCallbacks() {
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void doesNotOverwriteAliasChangedWhileLlmWasRunning() {
        BStock source = stock("英伟呆", "NVXX", "RULE", false);
        BStock current = stock("管理员刚改的名字", "MANUAL7", "MANUAL", true);
        when(mapper.selectById(1L)).thenReturn(source);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(aliasLlmService.generate(source)).thenReturn(
                new BStockAliasGenerator.Alias("月背晶格局", "MOON8", "架空简介", 2));

        BizException error = assertThrows(BizException.class, () -> service.regenerateAlias(1L));

        assertTrue(error.getMsg().contains("生成期间影子身份已被修改"));
        verify(mapper, never()).updateById(any(BStock.class));
    }

    private static BStock stock(String displayName, String displayCode, String source, boolean locked) {
        BStock stock = new BStock();
        stock.setId(1L);
        stock.setTicker("NVDA");
        stock.setName("NVIDIA Corporation");
        stock.setIndustry("Technology");
        stock.setDisplayName(displayName);
        stock.setDisplayCode(displayCode);
        stock.setDisplayLore("旧简介");
        stock.setAliasSource(source);
        stock.setAliasVersion(2);
        stock.setAliasLocked(locked);
        return stock;
    }
}
