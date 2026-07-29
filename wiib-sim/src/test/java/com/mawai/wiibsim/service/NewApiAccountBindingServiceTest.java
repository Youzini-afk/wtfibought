package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.NewApiIdentity;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewApiAccountBindingServiceTest {
    @Mock UserMapper userMapper;
    @Mock UserService userService;

    private NewApiAccountBindingService service;
    private NewApiIdentity identity;

    @BeforeEach
    void setUp() {
        service = new NewApiAccountBindingService(userMapper, userService);
        identity = new NewApiIdentity(42L, "main-user", "Main User", " https://cdn.example/avatar.png ", 0L, 500000L);
    }

    @Test
    void bindsUnboundAccountWithoutChangingItsLocalIdentity() {
        User local = localUser(7L, null);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(local);
        when(userService.updateById(local)).thenReturn(true);

        User result = service.bind(7L, identity);

        assertThat(result.getNewApiUserId()).isEqualTo(42L);
        assertThat(result.getUsername()).isEqualTo("legacy-user");
        assertThat(result.getAvatar()).isEqualTo("https://cdn.example/avatar.png");
        verify(userService).updateById(local);
    }

    @Test
    void sameBindingIsIdempotent() {
        User local = localUser(7L, 42L);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(local);

        assertThat(service.bind(7L, identity)).isSameAs(local);
        verify(userService, never()).updateById(local);
    }

    @Test
    void refusesToReplaceAnExistingDifferentBinding() {
        User local = localUser(7L, 99L);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(local);

        assertThatThrownBy(() -> service.bind(7L, identity))
                .isInstanceOf(BizException.class)
                .hasMessage("当前游戏账号已绑定其他主站账户");
        verify(userService, never()).updateById(local);
    }

    @Test
    void refusesIdentityAlreadyOwnedByAnotherGameAccount() {
        User local = localUser(7L, null);
        User owner = localUser(8L, 42L);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(local);
        when(userService.findByNewApiUserId(42L)).thenReturn(owner);

        assertThatThrownBy(() -> service.bind(7L, identity))
                .isInstanceOf(BizException.class)
                .hasMessage("该主站账户已绑定其他游戏账号");
        verify(userService, never()).updateById(local);
    }

    @Test
    void translatesConcurrentUniqueIndexLossToBusinessError() {
        User local = localUser(7L, null);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(local);
        when(userService.updateById(local)).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.bind(7L, identity))
                .isInstanceOf(BizException.class)
                .hasMessage("该主站账户已绑定其他游戏账号");
    }

    private User localUser(long id, Long newApiUserId) {
        User user = new User();
        user.setId(id);
        user.setUsername("legacy-user");
        user.setNewApiUserId(newApiUserId);
        return user;
    }
}
