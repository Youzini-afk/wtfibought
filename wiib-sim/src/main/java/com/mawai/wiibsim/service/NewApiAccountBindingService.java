package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.NewApiIdentity;
import com.mawai.wiibsim.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在本地事务中把一个已有游戏账号绑定到唯一的 New API 身份。 */
@Service
@RequiredArgsConstructor
public class NewApiAccountBindingService {
    private final UserMapper userMapper;
    private final UserService userService;

    @Transactional(rollbackFor = Exception.class)
    public User bind(long localUserId, NewApiIdentity identity) {
        User user = userMapper.selectByIdForUpdate(localUserId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);

        Long currentBinding = user.getNewApiUserId();
        if (currentBinding != null && currentBinding > 0) {
            if (currentBinding == identity.userId()) return user;
            throw new BizException(ErrorCode.FORBIDDEN.getCode(), "当前游戏账号已绑定其他主站账户");
        }

        User owner = userService.findByNewApiUserId(identity.userId());
        if (owner != null && !owner.getId().equals(localUserId)) {
            throw new BizException(ErrorCode.FORBIDDEN.getCode(), "该主站账户已绑定其他游戏账号");
        }

        user.setNewApiUserId(identity.userId());
        if (identity.avatarUrl() != null && !identity.avatarUrl().isBlank()) {
            user.setAvatar(identity.avatarUrl().trim());
        }
        try {
            if (!userService.updateById(user)) {
                throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            }
        } catch (DuplicateKeyException e) {
            // 唯一索引是最终并发边界：两个本地账号同时绑定同一主站身份时只允许一个成功。
            throw new BizException(ErrorCode.FORBIDDEN.getCode(), "该主站账户已绑定其他游戏账号");
        }
        return user;
    }
}
