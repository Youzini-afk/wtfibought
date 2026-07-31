package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock NewApiAccountBindingService accountBindingService;

    private NewApiIntegrationService service;
    private NewApiIntegrationConfig.Settings settings;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ExternalQuotaTransfer.class);
        settings = enabledSettings();
        when(config.isUsable()).thenReturn(true);
        when(config.isReconciliationConfigured()).thenReturn(true);
        when(config.snapshot()).thenReturn(settings);
        when(config.getQuotaPerUnit()).thenReturn(new BigDecimal("500000"));
        service = new NewApiIntegrationService(
                config, client, userService, transferMapper, settlementService, withdrawalService,
                accountBindingService);
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
        when(client.statusForReconciliation(any(), eq(settings))).thenAnswer(invocation -> {
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
        when(client.statusForReconciliation(transfer.getOperationId(), settings)).thenReturn(Optional.empty());
        when(client.debitForReconciliation(transfer.getOperationId(), 42L, 500000L, settings))
                .thenReturn(new NewApiQuotaResult(
                transfer.getOperationId(), 42L, "debit", 500000L, "completed", "", 500000L, true));

        service.reconcileOne(transfer);

        verify(client).debitForReconciliation(transfer.getOperationId(), 42L, 500000L, settings);
        verify(settlementService).settleDeposit(transfer.getOperationId(), 500000L);
    }

    @Test
    void terminalRemoteFailureIsRecordedWithoutLocalCredit() {
        ExternalQuotaTransfer transfer = pendingTransfer("deposit-operation-2");
        when(client.statusForReconciliation(transfer.getOperationId(), settings))
                .thenReturn(Optional.of(new NewApiQuotaResult(
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
        when(client.statusForReconciliation(transfer.getOperationId(), settings)).thenReturn(Optional.empty());
        when(client.creditForReconciliation(transfer.getOperationId(), 42L, 4_750_000L, settings))
                .thenReturn(new NewApiQuotaResult(
                transfer.getOperationId(), 42L, "credit", 4_750_000L,
                "completed", "", 9_750_000L, true));

        service.reconcileOne(transfer);

        verify(client).creditForReconciliation(transfer.getOperationId(), 42L, 4_750_000L, settings);
        verify(client, never()).debitForReconciliation(any(), anyLong(), anyLong(), any());
        verify(settlementService).settleWithdrawal(transfer.getOperationId(), 9_750_000L);
    }

    @Test
    void terminalWithdrawalFailureRefundsLocalGrossReservation() {
        ExternalQuotaTransfer transfer = pendingTransfer("withdrawal-operation-2");
        transfer.setDirection("WITHDRAWAL");
        when(client.statusForReconciliation(transfer.getOperationId(), settings))
                .thenReturn(Optional.of(new NewApiQuotaResult(
                transfer.getOperationId(), 42L, "credit", 500000L,
                "failed", "user_not_found", 0L, false)));

        service.reconcileOne(transfer);

        verify(settlementService).refundWithdrawal(
                transfer.getOperationId(), "failed", "user_not_found", "主站账户不存在");
        verify(transferMapper, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void existingSsoBindingIsReused() {
        NewApiIdentity identity = new NewApiIdentity(
                42L, "tester", "Tester", "https://youzi.today/api/user/avatar/42/hash.png", 1000L, 500000L);
        User existing = new User();
        existing.setId(7L);
        existing.setNewApiUserId(42L);
        existing.setUsername("legacy-user");
        when(client.exchangeCode("one-time-code")).thenReturn(identity);
        when(userService.findByNewApiUserId(42L)).thenReturn(existing);
        when(userService.updateById(existing)).thenReturn(true);

        User resolved = service.resolveSsoUser("one-time-code");

        assertThat(resolved).isSameAs(existing);
        assertThat(resolved.getUsername()).isEqualTo("Tester");
        assertThat(resolved.getAvatar()).isEqualTo("https://youzi.today/api/user/avatar/42/hash.png");
        verify(userService).updateById(existing);
        verify(userService, never()).save(any(User.class));
    }

    @Test
    void authenticatedAccountBindingExchangesAndValidatesOneTimeCode() {
        NewApiIdentity identity = new NewApiIdentity(
                42L, "tester", "Tester", "https://youzi.today/api/user/avatar/42/hash.png", 1000L, 500000L);
        User bound = new User();
        bound.setId(7L);
        bound.setUsername("legacy-user");
        bound.setNewApiUserId(42L);
        when(client.exchangeCode("bind-code")).thenReturn(identity);
        when(accountBindingService.bind(7L, identity)).thenReturn(bound);
        when(userService.updateById(bound)).thenReturn(true);

        User result = service.bindSsoUser(7L, " bind-code ");

        assertThat(result).isSameAs(bound);
        assertThat(result.getUsername()).isEqualTo("Tester");
        assertThat(result.getAvatar()).isEqualTo("https://youzi.today/api/user/avatar/42/hash.png");
        verify(accountBindingService).bind(7L, identity);
        verify(userService).updateById(bound);
    }

    @Test
    void profileSyncUsesStableSuffixWhenDisplayNameAlreadyBelongsToAnotherUser() {
        NewApiIdentity identity = new NewApiIdentity(42L, "tester", "Tester", "", 1000L, 500000L);
        User existing = new User();
        existing.setId(7L);
        existing.setNewApiUserId(42L);
        existing.setUsername("legacy-user");
        User nameOwner = new User();
        nameOwner.setId(8L);
        nameOwner.setUsername("Tester");
        when(client.exchangeCode("one-time-code")).thenReturn(identity);
        when(userService.findByNewApiUserId(42L)).thenReturn(existing);
        when(userService.findByUsername("Tester")).thenReturn(nameOwner);
        when(userService.updateById(existing)).thenReturn(true);

        User resolved = service.resolveSsoUser("one-time-code");

        assertThat(resolved.getUsername()).isEqualTo("Tester_42");
        verify(userService).updateById(existing);
    }

    @Test
    void disablingNewOperationsStillReconcilesAnExistingPendingTransfer() {
        NewApiIntegrationConfig.Settings disabledSettings = settings(false);
        when(config.isUsable()).thenReturn(false);
        when(config.snapshot()).thenReturn(disabledSettings);
        ExternalQuotaTransfer transfer = pendingTransfer("disabled-reconcile-operation");
        when(transferMapper.selectList(any())).thenReturn(List.of(transfer));
        when(client.statusForReconciliation(transfer.getOperationId(), disabledSettings))
                .thenReturn(Optional.of(new NewApiQuotaResult(
                        transfer.getOperationId(), 42L, "debit", 500000L,
                        "completed", "", 750000L, true)));

        service.reconcilePendingTransfers();

        verify(client).statusForReconciliation(transfer.getOperationId(), disabledSettings);
        verify(settlementService).settleDeposit(transfer.getOperationId(), 750000L);
    }

    @Test
    void disablingIntegrationBlocksNewSsoBindingAndDepositBeforeRemoteCalls() {
        when(config.isUsable()).thenReturn(false);

        assertThatThrownBy(() -> service.resolveSsoUser("login-code"))
                .hasMessageContaining("登录未启用");
        assertThatThrownBy(() -> service.bindSsoUser(7L, "bind-code"))
                .hasMessageContaining("登录未启用");
        assertThatThrownBy(() -> service.deposit(7L, BigDecimal.ONE))
                .hasMessageContaining("额度转入未启用");

        verify(client, never()).exchangeCode(any());
        verify(transferMapper, never()).insert(any(ExternalQuotaTransfer.class));
    }

    @Test
    void incompleteReconciliationConfigurationKeepsTransferPendingForRetry() {
        NewApiIntegrationConfig.Settings incomplete = new NewApiIntegrationConfig.Settings(
                false, "", "wtfib", "", new BigDecimal("500000"),
                false, new BigDecimal("0.50"), new BigDecimal("100.00"),
                new BigDecimal("1.00"), "Asia/Shanghai", "20:0.05,*:0.10");
        when(config.snapshot()).thenReturn(incomplete);
        ExternalQuotaTransfer transfer = pendingTransfer("incomplete-config-operation");

        service.reconcileOne(transfer);

        verify(transferMapper).scheduleRetry(
                eq(transfer.getOperationId()), eq("主站额度桥接配置暂不完整"), any(LocalDateTime.class));
        verify(client, never()).statusForReconciliation(any(), any());
    }

    private NewApiIntegrationConfig.Settings enabledSettings() {
        return settings(true);
    }

    private NewApiIntegrationConfig.Settings settings(boolean enabled) {
        return new NewApiIntegrationConfig.Settings(
                enabled,
                "https://youzi.today",
                "wtfib",
                "test-secret-12345678",
                new BigDecimal("500000"),
                true,
                new BigDecimal("0.50"),
                new BigDecimal("100.00"),
                new BigDecimal("1.00"),
                "Asia/Shanghai",
                "20:0.05,50:0.10,*:0.20");
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
