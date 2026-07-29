package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface ExternalQuotaTransferMapper extends BaseMapper<ExternalQuotaTransfer> {

    /**
     * Claims one pending transfer inside the caller's transaction. APPLYING is
     * never committed on its own: a crash/exception rolls the row back to
     * PENDING, while a successful transaction advances it to COMPLETED.
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE external_quota_transfer SET status = 'APPLYING', updated_at = NOW() " +
            "WHERE operation_id = #{operationId} AND status = 'PENDING' RETURNING id")
    Long claimPending(@Param("operationId") String operationId);

    @Update("UPDATE external_quota_transfer SET status = 'COMPLETED', remote_status = 'completed', " +
            "remote_quota_after = #{remoteQuotaAfter}, error_code = NULL, error_message = NULL, " +
            "completed_at = #{completedAt}, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'APPLYING'")
    int completeClaim(@Param("id") Long id,
                      @Param("remoteQuotaAfter") Long remoteQuotaAfter,
                      @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE external_quota_transfer SET status = 'FAILED', remote_status = #{remoteStatus}, " +
            "error_code = #{errorCode}, error_message = #{errorMessage}, completed_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'APPLYING'")
    int failClaim(@Param("id") Long id,
                  @Param("remoteStatus") String remoteStatus,
                  @Param("errorCode") String errorCode,
                  @Param("errorMessage") String errorMessage);

    @Update("UPDATE external_quota_transfer SET status = 'FAILED', remote_status = #{remoteStatus}, " +
            "error_code = #{errorCode}, error_message = #{errorMessage}, completed_at = NOW(), updated_at = NOW() " +
            "WHERE operation_id = #{operationId} AND status = 'PENDING'")
    int markFailed(@Param("operationId") String operationId,
                   @Param("remoteStatus") String remoteStatus,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    @Update("UPDATE external_quota_transfer SET attempt_count = attempt_count + 1, " +
            "error_message = #{errorMessage}, next_retry_at = #{nextRetryAt}, updated_at = NOW() " +
            "WHERE operation_id = #{operationId} AND status = 'PENDING'")
    int scheduleRetry(@Param("operationId") String operationId,
                      @Param("errorMessage") String errorMessage,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 当天已经预留的提现毛额；失败且已退款的记录不再占用额度。 */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM external_quota_transfer " +
            "WHERE user_id = #{userId} AND direction = 'WITHDRAWAL' AND business_date = #{businessDate} " +
            "AND status IN ('PENDING', 'APPLYING', 'COMPLETED')")
    BigDecimal sumReservedWithdrawals(@Param("userId") long userId,
                                      @Param("businessDate") LocalDate businessDate);

    @Select("SELECT COUNT(*) FROM external_quota_transfer " +
            "WHERE user_id = #{userId} AND direction = 'WITHDRAWAL' " +
            "AND status IN ('PENDING', 'APPLYING')")
    long countOpenWithdrawals(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM external_quota_transfer " +
            "WHERE user_id = #{userId} AND status IN ('PENDING', 'APPLYING')")
    long countOpenTransfers(@Param("userId") long userId);
}
