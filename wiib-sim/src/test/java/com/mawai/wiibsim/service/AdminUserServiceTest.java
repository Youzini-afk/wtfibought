package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.constant.UserAccess;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.AdminUserDTO;
import com.mawai.wiibsim.dto.AdminUserUpdateRequest;
import com.mawai.wiibsim.entity.AdminUserAudit;
import com.mawai.wiibsim.mapper.AdminUserAuditMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock UserMapper userMapper;
    @Mock UserService userService;
    @Mock AdminUserAuditMapper auditMapper;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userMapper, userService, auditMapper);
    }

    @Test
    void ownerCanDisableOrdinaryUserAndWritesAudit() {
        User owner = user(1L, UserAccess.ROLE_OWNER, UserAccess.STATUS_ACTIVE);
        User target = user(7L, UserAccess.ROLE_USER, UserAccess.STATUS_ACTIVE);
        when(userMapper.selectById(1L)).thenReturn(owner);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(target);
        when(userMapper.updateStatus(7L, UserAccess.STATUS_DISABLED)).thenReturn(1);

        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setStatus(UserAccess.STATUS_DISABLED);
        request.setReason("滥用处理");

        AdminUserDTO result = service.update(1L, 7L, request);

        assertThat(result.getStatus()).isEqualTo(UserAccess.STATUS_DISABLED);
        ArgumentCaptor<AdminUserAudit> audit = ArgumentCaptor.forClass(AdminUserAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("STATUS");
        assertThat(audit.getValue().getBeforeValue()).isEqualTo("1");
        assertThat(audit.getValue().getAfterValue()).isEqualTo("2");
        assertThat(audit.getValue().getReason()).isEqualTo("滥用处理");
    }

    @Test
    void adminCannotManagePeerAdmin() {
        User operator = user(5L, UserAccess.ROLE_ADMIN, UserAccess.STATUS_ACTIVE);
        User target = user(6L, UserAccess.ROLE_ADMIN, UserAccess.STATUS_ACTIVE);
        when(userMapper.selectById(5L)).thenReturn(operator);
        when(userMapper.selectByIdForUpdate(6L)).thenReturn(target);

        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setStatus(UserAccess.STATUS_DISABLED);
        request.setReason("test");

        assertThatThrownBy(() -> service.update(5L, 6L, request))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void ownerCannotMutateOwnerAccount() {
        User owner = user(1L, UserAccess.ROLE_OWNER, UserAccess.STATUS_ACTIVE);
        when(userMapper.selectById(1L)).thenReturn(owner);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(owner);

        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setStatus(UserAccess.STATUS_DISABLED);
        request.setReason("test");

        assertThatThrownBy(() -> service.update(1L, 1L, request))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void internalQuantAccountIsReadOnly() {
        User owner = user(1L, UserAccess.ROLE_OWNER, UserAccess.STATUS_ACTIVE);
        User target = user(9L, UserAccess.ROLE_USER, UserAccess.STATUS_ACTIVE);
        target.setLinuxDoId("internal:quant-fibo");
        when(userMapper.selectById(1L)).thenReturn(owner);
        when(userMapper.selectByIdForUpdate(9L)).thenReturn(target);

        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setMuteDays(7);
        request.setReason("test");

        assertThatThrownBy(() -> service.update(1L, 9L, request))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    private User user(long id, int role, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
