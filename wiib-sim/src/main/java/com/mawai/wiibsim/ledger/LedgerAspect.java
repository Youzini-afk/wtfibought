package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 资金记账切面。织在 UserMapper 的原子资金方法上——那是全项目唯一的资金出口
 * （全仓 grep 过：UPDATE "user" SET 只出现在 UserMapper），所以不可能漏账。
 * <p>
 * 记账 INSERT 刻意不 catch：吞掉异常会让事务不回滚，余额变了账没记，
 * 不变量当场破裂且无法自愈。INSERT 本地表失败本身就是系统级故障，让交易失败是对的。
 */
@Slf4j
@Aspect
@Component
@Order(2)
@RequiredArgsConstructor
public class LedgerAspect {

    private final UserLedgerMapper ledgerMapper;

    /** 维护方法级语义栈 */
    @Around("@annotation(ledger)")
    public Object aroundLedgerMethod(ProceedingJoinPoint point, Ledger ledger) throws Throwable {
        LedgerCtx.push(ledger.value());
        try {
            return point.proceed();
        } finally {
            LedgerCtx.pop();   // finally 保证异常路径也弹栈，避免线程复用串味
        }
    }

    /**
     * 资金出口：UserMapper 的原子资金方法。
     * 抽成命名 pointcut 是因为下面落账与丢标注两条 advice 必须切在<b>完全相同</b>的一组方法上——
     * 各写一份表达式，哪天只改了一边，另一条路径的标注就又开始泄漏。
     */
    @Pointcut("execution(* com.mawai.wiibsim.mapper.UserMapper.atomic*(..))")
    void walletMutation() {}

    /** 资金变动落账。返回 null 表示 SQL 条件不满足、没改成，不记 */
    @AfterReturning(pointcut = "walletMutation()", returning = "ret")
    public void recordUserWallet(JoinPoint point, Object ret) {
        // 【取标注必须是第一句，不能等到 ret != null 之后】
        // 一次性标注的语义是"标给下一次资金调用"，那次调用无论成功还是返 null 都算把它消费掉了。
        // ret == null 不是异常路径而是设计上的正常分支（资金费支付方就是靠返 null 判定余额不够、
        // 转去扣仓位保证金），另有约 10 个调用点直接丢弃返回值。放在判空之后取，
        // 这些路径的 mark 就泄漏到再下一笔上——资金费是单线程 for 循环逐仓位跑的，
        // 泄漏的 mark 会带着上一个仓位的 refType/refId 安到下一个用户头上，错标比无标更难查。
        LedgerCtx.Mark mark = LedgerCtx.takeMark();
        if (ret == null) return;

        String method = point.getSignature().getName();
        Object[] args = point.getArgs();
        var rows = LedgerRowMapping.rowsOf(method, args, ret);
        if (rows.isEmpty()) return;

        Long userId = (Long) args[0];
        LedgerBizType type = mark != null ? mark.type() : LedgerCtx.currentType();
        String remark = null;

        if (type == null) {
            type = LedgerBizType.UNKNOWN;
            remark = callerOf(point);
            log.warn("[Ledger] 资金变动无语义标注 userId={} method={} caller={}", userId, method, remark);
        }

        for (var row : rows) {
            UserLedger entry = new UserLedger();
            entry.setUserId(userId);
            entry.setWallet(row.wallet());
            entry.setBizType(type);
            entry.setDelta(row.delta());
            entry.setBalanceAfter(row.balanceAfter());
            entry.setRefType(mark != null ? mark.refType() : null);
            entry.setRefId(mark != null ? mark.refId() : null);
            entry.setSymbol(LedgerCtx.currentSymbol());
            entry.setRemark(remark);
            ledgerMapper.insert(entry);
        }
    }

    /**
     * SQL 抛异常（PG 死锁、锁超时、约束冲突、连接断）时把标注丢掉，<b>不记账</b>——抛异常意味着这笔钱没动。
     * <p>
     * 为什么两条路径都得清：@AfterReturning 在抛异常时整条 advice 根本不执行，
     * ONE_SHOT 就保持着已 set 的状态留在线程上。Tomcat/池化线程复用后，
     * 下一个请求里第一笔没标注的资金变动会直接继承上一个请求的 bizType/refType/refId。
     * 这不是"少记一笔"，是把 A 用户的语义安到 B 用户账本上，比无标注难查得多。
     * <p>
     * 用 @AfterThrowing 而不是把落账整条改 @Around：两条 advice 各管一件事、语义直白，
     * 而"漏改一边"这个唯一风险已经由共享的 walletMutation() pointcut 消掉了。
     * 删掉本方法会让 LedgerAspectTest.SQL抛异常时标注必须被丢弃 变红。
     */
    @AfterThrowing("walletMutation()")
    public void discardMarkOnFailure() {
        LedgerCtx.takeMark();
    }

    /** 兜底记录调用来源，方便事后补语义 */
    private static String callerOf(JoinPoint point) {
        return StackWalker.getInstance()
                .walk(s -> s.map(StackWalker.StackFrame::getClassName)
                        .filter(c -> c.startsWith("com.mawai.wiibsim.service"))
                        .findFirst()
                        .map(c -> c.substring(c.lastIndexOf('.') + 1))
                        .orElse("unknown"));
    }
}
