package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.BlackjackAccountMapper;
import com.mawai.wiibsim.mapper.BlackjackConvertLogMapper;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibsim.mapper.ExternalQuotaTransferMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.MinesGameMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.UserAssetSnapshotMapper;
import com.mawai.wiibsim.mapper.UserBuffMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.mapper.VideoPokerGameMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountPurgeTxTest {
    @Mock UserMapper userMapper;
    @Mock ExternalQuotaTransferMapper externalQuotaTransferMapper;
    @Mock FuturesPositionMapper futuresPositionMapper;
    @Mock FuturesOrderMapper futuresOrderMapper;
    @Mock CryptoPositionMapper cryptoPositionMapper;
    @Mock CryptoOrderMapper cryptoOrderMapper;
    @Mock PredictionBetMapper predictionBetMapper;
    @Mock BlackjackAccountMapper blackjackAccountMapper;
    @Mock BlackjackConvertLogMapper blackjackConvertLogMapper;
    @Mock MinesGameMapper minesGameMapper;
    @Mock VideoPokerGameMapper videoPokerGameMapper;
    @Mock UserAssetSnapshotMapper userAssetSnapshotMapper;
    @Mock UserBuffMapper userBuffMapper;
    @Mock UserLedgerMapper userLedgerMapper;
    @Mock UserService userService;

    @InjectMocks AccountPurgeTx purgeTx;

    @Test
    void locksUserAndRechecksExternalTransfersBeforeDeletingAnything() {
        User user = new User();
        user.setId(7L);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
        when(externalQuotaTransferMapper.countOpenTransfers(7L)).thenReturn(1L);

        assertThrows(BizException.class, () -> purgeTx.purge(7L));

        InOrder order = inOrder(userMapper, externalQuotaTransferMapper);
        order.verify(userMapper).selectByIdForUpdate(7L);
        order.verify(externalQuotaTransferMapper).countOpenTransfers(7L);
        verify(userMapper, never()).resetToInitial(7L, null);
        verifyNoInteractions(
                futuresPositionMapper,
                futuresOrderMapper,
                cryptoPositionMapper,
                cryptoOrderMapper,
                predictionBetMapper,
                blackjackAccountMapper,
                blackjackConvertLogMapper,
                minesGameMapper,
                videoPokerGameMapper,
                userAssetSnapshotMapper,
                userBuffMapper,
                userLedgerMapper,
                userService);
    }
}
