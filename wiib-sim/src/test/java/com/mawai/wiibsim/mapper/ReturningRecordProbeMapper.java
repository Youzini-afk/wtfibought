package com.mawai.wiibsim.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 闸门探针（测试专用）：验 MyBatis 能不能把 RETURNING 的多列映射进 record。
 * Task 4 要定义 3 个 record、4 个多钱包方法全走这条路，走不通或映错就得换设计，所以必须先钉死。
 * <p>
 * 为什么是探针而不是直接改 UserMapper.atomicFreezeBalance：本任务的闸门范围是"只改一个方法"，
 * 改第二个就得连带改它的调用点，那是 Task 4 的活。这里用一条和 atomicFreezeBalance 完全同款的
 * SQL（只多加 RETURNING）验机制，不动生产签名。
 * <p>
 * 为什么放在 com.mawai.wiibsim.mapper 包下：WiibSimApplication 的
 * {@code @MapperScan({"com.mawai.wiibsim.mapper", "com.mawai.wiibcommon.mapper"})} 按包名扫描，
 * 放测试自己的包里不会被注册。测试源码目录下的同名包会一起被扫到，故落在这里——别挪走。
 */
@Mapper
public interface ReturningRecordProbeMapper {

    /**
     * 组件顺序与下面 RETURNING 的列序严格对应：balance → frozen_balance。
     * MyBatis 是按列序依次填组件、不看列名，两个组件又都是 BigDecimal，写反了不会报错，
     * 只会把两个钱包的值对调。改任意一边都必须同步改另一边。
     */
    record FreezeResult(BigDecimal balance, BigDecimal frozenBalance) {
    }

    /** 与 UserMapper.atomicFreezeBalance 同款 SQL（可用减少、冻结增加），只多一个 RETURNING */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET balance = balance - #{amount}, frozen_balance = frozen_balance + #{amount}, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId} AND balance >= #{amount} " +
            "RETURNING balance, frozen_balance")
    FreezeResult atomicFreezeBalanceProbe(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
