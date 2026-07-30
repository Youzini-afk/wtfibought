package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.ExternalQuotaTransfer;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.NewApiIntegrationConfig;
import com.mawai.wiibsim.dto.ExternalWithdrawalPreviewDTO;
import com.mawai.wiibsim.mapper.ExternalQuotaTransferMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalWithdrawalServiceTest {
    @Mock NewApiIntegrationConfig config;
    @Mock UserService userService;
    @Mock UserMapper userMapper;
    @Mock CrossMarginService crossMarginService;
    @Mock ExternalQuotaTransferMapper transferMapper;

    private ExternalWithdrawalService service;

    @BeforeEach
    void setUp() {
        when(config.snapshot()).thenReturn(new NewApiIntegrationConfig.Settings(
                true,
                "https://youzi.today",
                "wtfib",
                "0123456789abcdef0123456789abcdef",
                new BigDecimal("500000"),
                true,
                new BigDecimal("0.50"),
                new BigDecimal("100.00"),
                new BigDecimal("1.00"),
                "Asia/Shanghai",
                "20:0.05,50:0.10,100:0.15,*:0.20"));
        lenient().when(transferMapper.countOpenWithdrawals(7L)).thenReturn(0L);

        service = new ExternalWithdrawalService(
                config, userService, userMapper, crossMarginService, transferMapper);
        ReflectionTestUtils.setField(service, "initialBalance", BigDecimal.ZERO);
    }

    @Test
    void reservePersistsTaxedTransferAndDebitsGrossAmount() {
        arrangePortfolio(new BigDecimal("100.00"), new BigDecimal("200.00"), BigDecimal.ZERO);
        when(transferMapper.insert(any(ExternalQuotaTransfer.class))).thenReturn(1);
        when(userMapper.atomicUpdateBalance(7L, new BigDecimal("-50.00")))
                .thenReturn(new BigDecimal("50.00"));
        ArgumentCaptor<ExternalQuotaTransfer> inserted = ArgumentCaptor.forClass(ExternalQuotaTransfer.class);

        ExternalQuotaTransfer transfer = service.reserve(7L, new BigDecimal("50.00"));

        verify(transferMapper).insert(inserted.capture());
        assertThat(transfer).isSameAs(inserted.getValue());
        assertThat(transfer.getDirection()).isEqualTo("WITHDRAWAL");
        assertThat(transfer.getAmount()).isEqualByComparingTo("50.00");
        assertThat(transfer.getFee()).isEqualByComparingTo("4.00");
        assertThat(transfer.getNetAmount()).isEqualByComparingTo("46.00");
        assertThat(transfer.getQuotaAmount()).isEqualTo(23_000_000L);
        verify(userMapper).atomicUpdateBalance(7L, new BigDecimal("-50.00"));
    }

    @Test
    void cumulativeReservationsCannotBypassProfitPercentage() {
        arrangePortfolio(new BigDecimal("60.00"), new BigDecimal("160.00"), new BigDecimal("40.00"));

        ExternalWithdrawalPreviewDTO preview = service.preview(7L, new BigDecimal("11.00"));

        assertThat(preview.maximumGrossAmount()).isEqualByComparingTo("10.00");
        assertThat(preview.requestAllowed()).isFalse();
        assertThat(preview.rejectionReason()).contains("10.00");

        assertThatThrownBy(() -> service.reserve(7L, new BigDecimal("11.00")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("10.00");
        verify(transferMapper, never()).insert(any(ExternalQuotaTransfer.class));
        verify(userMapper, never()).atomicUpdateBalance(any(), any());
    }

    @Test
    void secondWithdrawalWaitsUntilPreviousRemoteOutcomeIsFinal() {
        arrangePortfolio(new BigDecimal("100.00"), new BigDecimal("200.00"), BigDecimal.ZERO);
        when(transferMapper.countOpenWithdrawals(7L)).thenReturn(1L);

        ExternalWithdrawalPreviewDTO preview = service.preview(7L, new BigDecimal("10.00"));

        assertThat(preview.withdrawalPending()).isTrue();
        assertThat(preview.requestAllowed()).isFalse();
        assertThat(preview.rejectionReason()).contains("仍在处理中");
    }

    private void arrangePortfolio(BigDecimal balance,
                                  BigDecimal totalAssets,
                                  BigDecimal withdrawnToday) {
        User user = new User();
        user.setId(7L);
        user.setNewApiUserId(42L);
        user.setBalance(balance);
        user.setFrozenBalance(BigDecimal.ZERO);
        user.setGameBalance(BigDecimal.ZERO);
        user.setProtectedPrincipal(new BigDecimal("100.00"));
        user.setIsBankrupt(false);
        lenient().when(userService.getById(7L)).thenReturn(user);
        lenient().when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);

        UserDTO portfolio = new UserDTO();
        portfolio.setTotalAssets(totalAssets);
        portfolio.setBankrupt(false);
        when(userService.getUserPortfolio(7L)).thenReturn(portfolio);
        when(crossMarginService.snapshot(7L)).thenReturn(new CrossMarginService.CrossAccount(
                balance, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        when(transferMapper.sumReservedWithdrawals(eq(7L), any(LocalDate.class))).thenReturn(withdrawnToday);
    }
}
