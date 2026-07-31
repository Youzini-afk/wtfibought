package com.mawai.wiibsim.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.constant.UserAccess;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.AdminUserAuditDTO;
import com.mawai.wiibsim.dto.AdminUserDTO;
import com.mawai.wiibsim.dto.AdminUserStatsDTO;
import com.mawai.wiibsim.dto.AdminUserUpdateRequest;
import com.mawai.wiibsim.entity.AdminUserAudit;
import com.mawai.wiibsim.mapper.AdminUserAuditMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端用户查询与低风险账户治理；不提供删用户或直接改资金能力。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final LocalDateTime PERMANENT_MUTE_UNTIL = LocalDateTime.of(2099, 12, 31, 23, 59, 59);

    private final UserMapper userMapper;
    private final UserService userService;
    private final AdminUserAuditMapper auditMapper;

    public IPage<AdminUserDTO> page(long operatorId, String keyword, Integer role, Integer status,
                                    int pageNum, int pageSize) {
        User operator = requireAdmin(operatorId);
        int operatorRole = roleOf(operator);
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(pageSize, 100));

        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        if (role != null) {
            if (role != UserAccess.ROLE_USER && role != UserAccess.ROLE_ADMIN && role != UserAccess.ROLE_OWNER) {
                throw new BizException(ErrorCode.PARAM_ERROR);
            }
            query.eq(User::getRole, role);
        }
        if (status != null) {
            validateStatus(status);
            query.eq(User::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.trim();
            query.and(w -> {
                w.like(User::getUsername, q).or().like(User::getLinuxDoId, q);
                try {
                    long numeric = Long.parseLong(q);
                    w.or().eq(User::getId, numeric).or().eq(User::getNewApiUserId, numeric);
                } catch (NumberFormatException ignored) {
                    // 非数字关键词只查用户名与登录标识。
                }
            });
        }
        query.orderByDesc(User::getId);
        Page<User> users = new Page<>(safePage, safeSize);
        userMapper.selectPage(users, query);
        return users.convert(user -> toDTO(user, operatorRole));
    }

    public AdminUserStatsDTO stats(long operatorId) {
        requireAdmin(operatorId);
        LocalDateTime now = LocalDateTime.now();
        AdminUserStatsDTO stats = new AdminUserStatsDTO();
        stats.setTotal(userMapper.selectCount(new LambdaQueryWrapper<>()));
        stats.setActive(userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, UserAccess.STATUS_ACTIVE)));
        stats.setDisabled(userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, UserAccess.STATUS_DISABLED)));
        stats.setAdmins(userMapper.selectCount(new LambdaQueryWrapper<User>()
                .ge(User::getRole, UserAccess.ROLE_ADMIN)));
        stats.setMuted(userMapper.selectCount(new LambdaQueryWrapper<User>()
                .gt(User::getMutedUntil, now)));
        stats.setBankrupt(userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getIsBankrupt, true)));
        return stats;
    }

    public UserDTO portfolio(long operatorId, long targetUserId) {
        requireAdmin(operatorId);
        return userService.getUserPortfolio(targetUserId);
    }

    public IPage<AdminUserAuditDTO> audits(long operatorId, long targetUserId, int pageNum, int pageSize) {
        requireAdmin(operatorId);
        requireUser(targetUserId);
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        Page<AdminUserAudit> page = new Page<>(safePage, safeSize);
        auditMapper.selectPage(page, new LambdaQueryWrapper<AdminUserAudit>()
                .eq(AdminUserAudit::getTargetUserId, targetUserId)
                .orderByDesc(AdminUserAudit::getId));

        Set<Long> operatorIds = page.getRecords().stream()
                .map(AdminUserAudit::getOperatorUserId)
                .collect(Collectors.toSet());
        Map<Long, User> operators = operatorIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(operatorIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        return page.convert(audit -> toAuditDTO(audit, operators.get(audit.getOperatorUserId())));
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminUserDTO update(long operatorId, long targetUserId, AdminUserUpdateRequest request) {
        if (request == null) throw new BizException(ErrorCode.PARAM_ERROR);
        User operator = requireAdmin(operatorId);
        User target = userMapper.selectByIdForUpdate(targetUserId);
        if (target == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        int operatorRole = roleOf(operator);
        if (!canManage(operatorRole, target)) throw new BizException(ErrorCode.FORBIDDEN);

        String reason = requireReason(request.getReason());
        boolean changed = false;
        boolean kickAfterCommit = false;

        if (request.getStatus() != null) {
            int next = request.getStatus();
            validateStatus(next);
            int before = UserAccess.normalizeStatus(target.getStatus());
            if (before != next) {
                userMapper.updateStatus(targetUserId, next);
                target.setStatus(next);
                recordAudit(operatorId, targetUserId, "STATUS", String.valueOf(before), String.valueOf(next), reason);
                changed = true;
                kickAfterCommit = true;
            }
        }

        if (request.getRole() != null) {
            if (operatorRole != UserAccess.ROLE_OWNER) throw new BizException(ErrorCode.FORBIDDEN);
            int next = request.getRole();
            if (next != UserAccess.ROLE_USER && next != UserAccess.ROLE_ADMIN) {
                throw new BizException(ErrorCode.PARAM_ERROR);
            }
            int before = roleOf(target);
            if (before != next) {
                userMapper.updateRole(targetUserId, next);
                target.setRole(next);
                recordAudit(operatorId, targetUserId, "ROLE", String.valueOf(before), String.valueOf(next), reason);
                changed = true;
                kickAfterCommit = true;
            }
        }

        if (request.getMuteDays() != null) {
            int days = request.getMuteDays();
            if (days < -1 || days > 3650) throw new BizException(ErrorCode.PARAM_ERROR);
            LocalDateTime before = target.getMutedUntil();
            LocalDateTime next = days == 0
                    ? null
                    : days == -1 ? PERMANENT_MUTE_UNTIL : LocalDateTime.now().plusDays(days);
            if (!java.util.Objects.equals(before, next)) {
                userMapper.updateMutedUntil(targetUserId, next);
                target.setMutedUntil(next);
                recordAudit(operatorId, targetUserId, "MUTE", valueOf(before), valueOf(next), reason);
                changed = true;
            }
        }

        if (!changed) throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "没有可保存的变更");
        target.setUpdatedAt(LocalDateTime.now());
        if (kickAfterCommit) kickAfterCommit(targetUserId);
        log.info("管理员治理用户 operatorId={} targetId={} status={} role={} muteDays={}",
                operatorId, targetUserId, request.getStatus(), request.getRole(), request.getMuteDays());
        return toDTO(target, operatorRole);
    }

    private User requireAdmin(long operatorId) {
        User operator = requireUser(operatorId);
        if (!UserAccess.isAdmin(roleOf(operator))) throw new BizException(ErrorCode.FORBIDDEN);
        return operator;
    }

    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        return user;
    }

    private int roleOf(User user) {
        return UserAccess.normalizeRole(user.getId(), user.getRole());
    }

    private boolean canManage(int operatorRole, User target) {
        return !isSystemAccount(target)
                && target.getId() != UserAccess.OWNER_USER_ID
                && operatorRole > roleOf(target);
    }

    private boolean isSystemAccount(User user) {
        return user.getLinuxDoId() != null && user.getLinuxDoId().startsWith("internal:");
    }

    private void validateStatus(int status) {
        if (status != UserAccess.STATUS_ACTIVE && status != UserAccess.STATUS_DISABLED) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    private String requireReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isBlank() || reason.length() > 200) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "请填写不超过 200 字的管理原因");
        }
        return reason;
    }

    private void recordAudit(long operatorId, long targetUserId, String action,
                             String before, String after, String reason) {
        AdminUserAudit audit = new AdminUserAudit();
        audit.setOperatorUserId(operatorId);
        audit.setTargetUserId(targetUserId);
        audit.setAction(action);
        audit.setBeforeValue(before);
        audit.setAfterValue(after);
        audit.setReason(reason);
        auditMapper.insert(audit);
    }

    private void kickAfterCommit(long targetUserId) {
        Runnable kick = () -> {
            try {
                StpUtil.kickout(targetUserId);
            } catch (Exception e) {
                log.warn("用户状态或角色已提交，但踢出旧会话失败 targetId={}: {}", targetUserId, e.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            kick.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                kick.run();
            }
        });
    }

    private AdminUserDTO toDTO(User user, int operatorRole) {
        AdminUserDTO dto = new AdminUserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setAvatar(user.getAvatar());
        dto.setRole(roleOf(user));
        dto.setStatus(UserAccess.normalizeStatus(user.getStatus()));
        dto.setLinuxDoId(user.getLinuxDoId());
        dto.setNewApiUserId(user.getNewApiUserId());
        dto.setLoginProvider(loginProvider(user));
        dto.setBalance(orZero(user.getBalance()));
        dto.setFrozenBalance(orZero(user.getFrozenBalance()));
        dto.setGameBalance(orZero(user.getGameBalance()));
        dto.setProtectedPrincipal(orZero(user.getProtectedPrincipal()));
        dto.setMarginLoanPrincipal(orZero(user.getMarginLoanPrincipal()));
        dto.setMarginInterestAccrued(orZero(user.getMarginInterestAccrued()));
        dto.setBankrupt(Boolean.TRUE.equals(user.getIsBankrupt()));
        dto.setBankruptCount(user.getBankruptCount() == null ? 0 : user.getBankruptCount());
        dto.setMutedUntil(user.getMutedUntil());
        dto.setMuted(user.getMutedUntil() != null && user.getMutedUntil().isAfter(LocalDateTime.now()));
        dto.setProfilePublic(!Boolean.FALSE.equals(user.getProfilePublic()));
        dto.setCreatedAt(user.getCreatedAt());
        dto.setUpdatedAt(user.getUpdatedAt());
        dto.setLastLoginAt(user.getLastLoginAt());
        dto.setSystemAccount(isSystemAccount(user));
        dto.setManageable(canManage(operatorRole, user));
        dto.setRoleEditable(operatorRole == UserAccess.ROLE_OWNER && canManage(operatorRole, user));
        return dto;
    }

    private AdminUserAuditDTO toAuditDTO(AdminUserAudit audit, User operator) {
        AdminUserAuditDTO dto = new AdminUserAuditDTO();
        dto.setId(audit.getId());
        dto.setOperatorUserId(audit.getOperatorUserId());
        dto.setOperatorUsername(operator == null ? null : operator.getUsername());
        dto.setTargetUserId(audit.getTargetUserId());
        dto.setAction(audit.getAction());
        dto.setBeforeValue(audit.getBeforeValue());
        dto.setAfterValue(audit.getAfterValue());
        dto.setReason(audit.getReason());
        dto.setCreatedAt(audit.getCreatedAt());
        return dto;
    }

    private String loginProvider(User user) {
        if (isSystemAccount(user)) return "SYSTEM";
        if (user.getNewApiUserId() != null) return "NEW_API";
        if (user.getLinuxDoId() != null && !"local-admin".equals(user.getLinuxDoId())) return "LINUX_DO";
        if (user.getPasswordHash() != null) return "PASSWORD";
        return "LOCAL";
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String valueOf(Object value) {
        return value == null ? null : value.toString();
    }
}
