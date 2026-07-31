package com.mawai.wiibsim.event;

import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAssetsChangedAspectTest {

    @Test
    void successfulZeroBalanceMutationStillPublishesAnAssetChange() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        UserMapper target = mock(UserMapper.class);
        when(target.atomicUpdateBalance(7L, BigDecimal.TEN.negate())).thenReturn(BigDecimal.ZERO);
        UserMapper proxy = proxy(target, new UserAssetsChangedAspect(publisher));

        proxy.atomicUpdateBalance(7L, BigDecimal.TEN.negate());

        verify(publisher).publishEvent(new UserAssetsChangedEvent(7L));
    }

    @Test
    void positionUpsertPublishesButRejectedAtomicMutationDoesNot() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CryptoPositionMapper target = mock(CryptoPositionMapper.class);
        UserAssetsChangedAspect aspect = new UserAssetsChangedAspect(publisher);
        CryptoPositionMapper proxy = proxy(target, aspect);

        proxy.upsertPosition(7L, "AAPLBUSDT", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO);
        verify(publisher).publishEvent(new UserAssetsChangedEvent(7L));

        when(target.atomicReduceQuantity(8L, "AAPLBUSDT", BigDecimal.ONE)).thenReturn(0);
        proxy.atomicReduceQuantity(8L, "AAPLBUSDT", BigDecimal.ONE);
        verify(publisher, never()).publishEvent(new UserAssetsChangedEvent(8L));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, UserAssetsChangedAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }
}
