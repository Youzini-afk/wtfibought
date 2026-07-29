package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import com.mawai.wiibsim.mapper.ExternalQuotaTransferMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalQuotaSettlementServiceTest {
    @Mock ExternalQuotaTransferMapper transferMapper;
    @Mock UserMapper userMapper;

    private ExternalQuotaSettlementService service;

    @BeforeEach
    void setUp() {
        service = new ExternalQuotaSettlementService(transferMapper, userMapper);
    }

    @Test
    void completedDepositCreditsBalanceAndProtectedPrincipalThroughOneAtomicPrimitive() {
        ExternalQuotaTransfer transfer = deposit(7L, "12.50");
        when(transferMapper.claimPending("op-deposit-1")).thenReturn(1L);
        when(transferMapper.selectById(1L)).thenReturn(transfer);
        when(userMapper.atomicApplyExternalDeposit(7L, new BigDecimal("12.50")))
                .thenReturn(new BigDecimal("112.50"));
        when(transferMapper.completeClaim(eq(1L), eq(900000L), any(LocalDateTime.class))).thenReturn(1);

        assertThat(service.settleDeposit("op-deposit-1", 900000L)).isTrue();

        verify(userMapper).atomicApplyExternalDeposit(7L, new BigDecimal("12.50"));
        verify(userMapper, never()).atomicUpdateBalance(any(), any());
    }

    @Test
    void replayThatCannotClaimDoesNotMoveEitherBalanceOrPrincipal() {
        when(transferMapper.claimPending("op-deposit-1")).thenReturn(null);

        assertThat(service.settleDeposit("op-deposit-1", 900000L)).isFalse();

        verify(userMapper, never()).atomicApplyExternalDeposit(any(), any());
        verify(transferMapper, never()).completeClaim(any(), any(), any());
    }

    @Test
    void completedWithdrawalOnlyAdvancesStatusBecauseGrossWasAlreadyReserved() {
        ExternalQuotaTransfer transfer = withdrawal(7L, "50.00");
        when(transferMapper.claimPending("op-withdrawal-1")).thenReturn(2L);
        when(transferMapper.selectById(2L)).thenReturn(transfer);
        when(transferMapper.completeClaim(eq(2L), eq(1_000_000L), any(LocalDateTime.class))).thenReturn(1);

        assertThat(service.settleWithdrawal("op-withdrawal-1", 1_000_000L)).isTrue();

        verify(userMapper, never()).atomicUpdateBalance(any(), any());
        verify(transferMapper).completeClaim(eq(2L), eq(1_000_000L), any(LocalDateTime.class));
    }

    @Test
    void terminalWithdrawalFailureRefundsGrossExactlyOnce() {
        ExternalQuotaTransfer transfer = withdrawal(7L, "50.00");
        when(transferMapper.claimPending("op-withdrawal-1")).thenReturn(2L).thenReturn(null);
        when(transferMapper.selectById(2L)).thenReturn(transfer);
        when(userMapper.atomicUpdateBalance(7L, new BigDecimal("50.00")))
                .thenReturn(new BigDecimal("150.00"));
        when(transferMapper.failClaim(2L, "failed", "remote_rejected", "主站拒绝"))
                .thenReturn(1);

        assertThat(service.refundWithdrawal(
                "op-withdrawal-1", "failed", "remote_rejected", "主站拒绝")).isTrue();
        assertThat(service.refundWithdrawal(
                "op-withdrawal-1", "failed", "remote_rejected", "主站拒绝")).isFalse();

        verify(userMapper).atomicUpdateBalance(7L, new BigDecimal("50.00"));
        verify(transferMapper).failClaim(2L, "failed", "remote_rejected", "主站拒绝");
    }

    private ExternalQuotaTransfer deposit(long userId, String amount) {
        ExternalQuotaTransfer transfer = new ExternalQuotaTransfer();
        transfer.setId(1L);
        transfer.setOperationId("op-deposit-1");
        transfer.setDirection("DEPOSIT");
        transfer.setUserId(userId);
        transfer.setAmount(new BigDecimal(amount));
        return transfer;
    }

    private ExternalQuotaTransfer withdrawal(long userId, String amount) {
        ExternalQuotaTransfer transfer = new ExternalQuotaTransfer();
        transfer.setId(2L);
        transfer.setOperationId("op-withdrawal-1");
        transfer.setDirection("WITHDRAWAL");
        transfer.setUserId(userId);
        transfer.setAmount(new BigDecimal(amount));
        return transfer;
    }
}
