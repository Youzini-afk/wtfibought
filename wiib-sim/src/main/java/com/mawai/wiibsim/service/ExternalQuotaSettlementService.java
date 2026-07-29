package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.mapper.ExternalQuotaTransferMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ExternalQuotaSettlementService {
    private final ExternalQuotaTransferMapper transferMapper;
    private final UserMapper userMapper;

    /**
     * Locally credits a remotely completed deposit exactly once. The PENDING
     * claim, balance mutation, ledger row, and COMPLETED transition share one
     * database transaction. A crash rolls all four changes back together.
     */
    @Transactional(rollbackFor = Exception.class)
    @Ledger(LedgerBizType.EXTERNAL_DEPOSIT)
    public boolean settleDeposit(String operationId, long remoteQuotaAfter) {
        Long claimedId = transferMapper.claimPending(operationId);
        if (claimedId == null) return false;

        ExternalQuotaTransfer transfer = transferMapper.selectById(claimedId);
        if (transfer == null || !"DEPOSIT".equals(transfer.getDirection())) {
            throw new IllegalStateException("claimed external deposit is missing or invalid");
        }
        LedgerCtx.mark(
                LedgerBizType.EXTERNAL_DEPOSIT,
                "EXTERNAL_QUOTA_TRANSFER",
                claimedId);
        BigDecimal balanceAfter = userMapper.atomicApplyExternalDeposit(transfer.getUserId(), transfer.getAmount());
        if (balanceAfter == null) {
            throw new IllegalStateException("external deposit user does not exist");
        }
        int completed = transferMapper.completeClaim(claimedId, remoteQuotaAfter, LocalDateTime.now());
        if (completed != 1) {
            throw new IllegalStateException("external deposit completion transition failed");
        }
        return true;
    }

    /** 远端已入账时，只推进本地状态；毛额在创建提现记录时已经预留扣除。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean settleWithdrawal(String operationId, long remoteQuotaAfter) {
        Long claimedId = transferMapper.claimPending(operationId);
        if (claimedId == null) return false;

        ExternalQuotaTransfer transfer = requireWithdrawal(claimedId);
        int completed = transferMapper.completeClaim(claimedId, remoteQuotaAfter, LocalDateTime.now());
        if (completed != 1) {
            throw new IllegalStateException("external withdrawal completion transition failed");
        }
        return true;
    }

    /**
     * 远端明确拒绝提现时，把本地预留的完整毛额（含原计划税额）精确退回。
     * claim、退款账本与 FAILED 状态在同一个事务中，重放不会重复退款。
     */
    @Transactional(rollbackFor = Exception.class)
    @Ledger(LedgerBizType.EXTERNAL_WITHDRAWAL_REFUND)
    public boolean refundWithdrawal(String operationId,
                                    String remoteStatus,
                                    String errorCode,
                                    String errorMessage) {
        Long claimedId = transferMapper.claimPending(operationId);
        if (claimedId == null) return false;

        ExternalQuotaTransfer transfer = requireWithdrawal(claimedId);
        LedgerCtx.mark(
                LedgerBizType.EXTERNAL_WITHDRAWAL_REFUND,
                "EXTERNAL_QUOTA_TRANSFER",
                claimedId);
        BigDecimal balanceAfter = userMapper.atomicUpdateBalance(transfer.getUserId(), transfer.getAmount());
        if (balanceAfter == null) {
            throw new IllegalStateException("external withdrawal refund user does not exist");
        }
        int failed = transferMapper.failClaim(
                claimedId, remoteStatus, errorCode, errorMessage);
        if (failed != 1) {
            throw new IllegalStateException("external withdrawal refund transition failed");
        }
        return true;
    }

    private ExternalQuotaTransfer requireWithdrawal(Long claimedId) {
        ExternalQuotaTransfer transfer = transferMapper.selectById(claimedId);
        if (transfer == null || !"WITHDRAWAL".equals(transfer.getDirection())) {
            throw new IllegalStateException("claimed external withdrawal is missing or invalid");
        }
        return transfer;
    }
}
