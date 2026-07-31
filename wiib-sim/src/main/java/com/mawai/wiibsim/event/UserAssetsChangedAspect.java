package com.mawai.wiibsim.event;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 从真正写入资金/现货持仓的 Mapper 出口发布资产变动事件。
 * 事件在事务提交后才会使快照失效，因此回滚不会污染缓存，业务服务也无需逐个手工补调用。
 */
@Aspect
@Component
@Order(3)
@RequiredArgsConstructor
public class UserAssetsChangedAspect {

    private final ApplicationEventPublisher eventPublisher;

    @Pointcut("execution(* com.mawai.wiibsim.mapper.UserMapper.atomic*(..)) " +
            "|| execution(* com.mawai.wiibsim.mapper.UserMapper.markBankrupt(..)) " +
            "|| execution(* com.mawai.wiibsim.mapper.UserMapper.resetAfterBankruptcy(..)) " +
            "|| execution(* com.mawai.wiibsim.mapper.UserMapper.resetToInitial(..))")
    void userAssetMutation() {
    }

    @AfterReturning(pointcut = "userAssetMutation()", returning = "result")
    public void publishUserMutation(JoinPoint point, Object result) {
        if (mutationSucceeded(result)) publish(firstUserId(point));
    }

    @Pointcut("execution(* com.mawai.wiibsim.mapper.CryptoPositionMapper.atomic*(..))")
    void cryptoPositionMutation() {
    }

    @AfterReturning(pointcut = "cryptoPositionMutation()", returning = "result")
    public void publishPositionMutation(JoinPoint point, Object result) {
        if (mutationSucceeded(result)) publish(firstUserId(point));
    }

    @AfterReturning("execution(* com.mawai.wiibsim.mapper.CryptoPositionMapper.upsertPosition(..))")
    public void publishPositionUpsert(JoinPoint point) {
        publish(firstUserId(point));
    }

    private void publish(Long userId) {
        if (userId != null) eventPublisher.publishEvent(new UserAssetsChangedEvent(userId));
    }

    private static Long firstUserId(JoinPoint point) {
        Object[] args = point.getArgs();
        if (args.length == 0 || !(args[0] instanceof Number number)) return null;
        return number.longValue();
    }

    private static boolean mutationSucceeded(Object result) {
        if (result == null) return false;
        if (result instanceof Integer count) return count > 0;
        if (result instanceof Long count) return count > 0;
        if (result instanceof Short count) return count > 0;
        if (result instanceof Byte count) return count > 0;
        // BigDecimal 等资金方法返回的是“变动后余额”，0 或负数也可能是一次成功更新。
        return true;
    }
}
