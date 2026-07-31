package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.AdminUserAuditDTO;
import com.mawai.wiibsim.dto.AdminUserDTO;
import com.mawai.wiibsim.dto.AdminUserStatsDTO;
import com.mawai.wiibsim.dto.AdminUserUpdateRequest;
import com.mawai.wiibsim.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户管理：前端隐藏只是体验，真正权限边界由类级 @RequireAdmin 强制执行。 */
@RestController
@RequireAdmin
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public Result<IPage<AdminUserDTO>> page(
            @CurrentUserId Long operatorId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer role,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(adminUserService.page(operatorId, keyword, role, status, pageNum, pageSize));
    }

    @GetMapping("/stats")
    public Result<AdminUserStatsDTO> stats(@CurrentUserId Long operatorId) {
        return Result.ok(adminUserService.stats(operatorId));
    }

    @GetMapping("/{id}/portfolio")
    public Result<UserDTO> portfolio(@CurrentUserId Long operatorId, @PathVariable long id) {
        return Result.ok(adminUserService.portfolio(operatorId, id));
    }

    @GetMapping("/{id}/audits")
    public Result<IPage<AdminUserAuditDTO>> audits(
            @CurrentUserId Long operatorId,
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(adminUserService.audits(operatorId, id, pageNum, pageSize));
    }

    @PutMapping("/{id}")
    public Result<AdminUserDTO> update(
            @CurrentUserId Long operatorId,
            @PathVariable long id,
            @RequestBody AdminUserUpdateRequest request) {
        return Result.ok(adminUserService.update(operatorId, id, request));
    }
}
