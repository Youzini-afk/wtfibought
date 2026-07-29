package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.ExternalQuotaTransferDTO;
import com.mawai.wiibsim.dto.NewApiIdentity;
import com.mawai.wiibsim.dto.NewApiQuotaResult;
import com.mawai.wiibsim.mapper.ExternalQuotaTransferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NewApiIntegrationServiceTest {
    @Mock NewApiIntegrationConfig config;
    @Mock NewApiClient client;
    @Mock UserService userService;
    @Mock ExternalQuotaTransferMapper transferMapper;
    @Mock ExternalQuotaSettlementService settlementService;
    @Mock ExternalWithdrawalService withdrawalService;

    private NewApiIntegrationService service;

    @BeforeEach
    void setUp() {
        when(config.isUsable()).thenReturn(true);
        when(config.getQuotaPerUnit()).thenReturn(new BigDecimal("500000"));
        service = new NewApiIntegrationService(
                config, client, userService, transferMapper, settlementService, withdrawalService);
    }

    @Test
    void depositPersistsBeforeRemoteDebitAndSettlesExactQuotaAmount() {
        User user = new User();
        user.setId(7L);
        user.setNewApiUserId(42L);
        when(userService.getById(7L)).thenReturn(user);

        ArgumentCaptor<ExternalQuotaTransfer> inserted = ArgumentCaptor.forClass(ExternalQuotaTransfer.class);
        when(transferMapper.insert(inserted.capture())).thenAnswer(invocation -> {
            ExternalQuotaTransfer transfer = invocation.getArgument(0);
            transfer.setId(99L);
            return 1;
        });
        when(client.status(any())).thenAnswer(invocation -> {
            String operationId = invocation.getArgument(0);
            return Optional.of(new NewApiQuotaResult(
                    operationId, 42L, "debit", 625000L, "completed", "", 900000L, true));
        });
        when(transferMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            ExternalQuotaTransfer completed = inserted.getValue();
            completed.setStatus("COMPLETED");
            completed.setRemoteQuotaAfter(900000L);
            completed.setCompletedAt(LocalDateTime.now());
            return completed;
        });

        ExternalQuotaTransferDTO result = service.deposit(7L, new BigDecimal("1.25"));

        ExternalQuotaTransfer transfer = inserted.getValue();
        assertThat(transfer.getStatus()).isEqualTo("COMPLETED");
        assertThat(transfer.getAmount()).isEqualByComparingTo("1.25");
        assertThat(transfer.getQuotaAmount()).isEqualTo(625000L);
        verify(settlementService).settleDeposit(transfer.getOperationId(), 900000L);
        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    @Test
    void reconciliationRetriesSameOperationWhenRemoteStatusIsMissing() {
        ExternalQuotaTransfer transfer = pendingTransfer("deposit-operation-1");
        when(client.status(transfer.getOperationId())).thenReturn(Optional.empty());
        when(client.debit(transfer.getOperationId(), 42L, 500000L)).thenReturn(new NewApiQuotaResult(
                transfer.getOperationId(), 42L, "debit", 500000L, "completed", "", 500000L, true));

        service.reconcileOne(transfer);

        verify(client).debit(transfer.getOperationId(), 42L, 500000L);
        verify(settlementService).settleDeposit(transfer.getOperationId(), 500000L);
    }

    @Test
    void terminalRemoteFailureIsRecordedWithoutLocalCredit() {
        ExternalQuotaTransfer transfer = pendingTransfer("deposit-operation-2");
        when(client.status(transfer.getOperationId())).thenReturn(Optional.of(new NewApiQuotaResult(
                transfer.getOperationId(), 42L, "debit", 500000L,
                "failed", "insufficient_quota", 10L, false)));

        service.reconcileOne(transfer);

        verify(transferMapper).markFailed(
                transfer.getOperationId(), "failed", "insufficient_quota", "主站额度不足");
        verify(settlementService, never()).settleDeposit(any(), anyLong());
    }

    @Test
    void withdrawalUsesRemoteCreditAndOnlyCompletesLocalReservation() {
        ExternalQuotaTransfer transfer = pendingTransfer("withdrawal-operation-1");
        transfer.setDirection("WITHDRAWAL");
        transfer.setAmount(new BigDecimal("10.00"));
        transfer.setQuotaAmount(4_750_000L);
        when(client.status(transfer.getOperationId())).thenReturn(Optional.empty());
        when(client.credit(transfer.getOperationId(), 42L, 4_750_000L)).thenReturn(new NewApiQuotaResult(
                transfer.getOperationId(), 42L, "credit", 4_750_000L,
                "completed", "", 9_750_000L, true));

        service.reconcileOne(transfer);

        verify(client).credit(transfer.getOperationId(), 42L, 4_750_000L);
        verify(client, never()).debit(any(), anyLong(), anyLong());
        verify(settlementService).settleWithdrawal(transfer.getOperationId(), 9_750_000L);
    }

    @Test
    void terminalWithdrawalFailureRefundsLocalGrossReservation() {
        ExternalQuotaTransfer transfer = pendingTransfer("withdrawal-operation-2");
        transfer.setDirection("WITHDRAWAL");
        when(client.status(transfer.getOperationId())).thenReturn(Optional.of(new NewApiQuotaResult(
                transfer.getOperationId(), 42L, "credit", 500000L,
                "failed", "user_not_found", 0L, false)));

        service.reconcileOne(transfer);

        verify(settlementService).refundWithdrawal(
                transfer.getOperationId(), "failed", "user_not_found", "主站账户不存在");
        verify(transferMapper, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void existingSsoBindingIsReused() {
        NewApiIdentity identity = new NewApiIdentity(42L, "tester", "Tester", "", 1000L, 500000L);
        User existing = new User();
        existing.setId(7L);
        existing.setNewApiUserId(42L);
        existing.setUsername("tester");
        when(client.exchangeCode("one-time-code")).thenReturn(identity);
        when(userService.findByNewApiUserId(42L)).thenReturn(existing);

        User resolved = service.resolveSsoUser("one-time-code");

        assertThat(resolved).isSameAs(existing);
        verify(userService, never()).save(any(User.class));
    }

    private ExternalQuotaTransfer pendingTransfer(String operationId) {
        ExternalQuotaTransfer transfer = new ExternalQuotaTransfer();
        transfer.setId(1L);
        transfer.setOperationId(operationId);
        transfer.setUserId(7L);
        transfer.setNewApiUserId(42L);
        transfer.setDirection("DEPOSIT");
        transfer.setAmount(BigDecimal.ONE);
        transfer.setQuotaAmount(500000L);
        transfer.setStatus("PENDING");
        transfer.setAttemptCount(0);
        return transfer;
    }
}
