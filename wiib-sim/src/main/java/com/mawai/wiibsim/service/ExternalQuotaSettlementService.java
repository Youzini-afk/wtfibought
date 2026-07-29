package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibsim.ledger.Ledger;
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
}
