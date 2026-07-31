package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.dto.BStockAdminDTO;
import com.mawai.wiibsim.dto.BStockBatchStatusRequest;
import com.mawai.wiibsim.dto.BStockCatalogSyncResult;
import com.mawai.wiibsim.dto.UpdateBStockAdminRequest;
import com.mawai.wiibsim.service.BStockAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 仅平台所有者与管理员可维护；普通用户只读公开影子市场接口。 */
@RestController
@RequireAdmin
@RequiredArgsConstructor
@RequestMapping("/api/admin/bstock")
public class BStockAdminController {

    private final BStockAdminService adminService;

    @GetMapping
    public Result<IPage<BStockAdminDTO>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(adminService.page(status, keyword, pageNum, pageSize));
    }

    @PutMapping("/{id}")
    public Result<BStockAdminDTO> update(@PathVariable Long id, @RequestBody UpdateBStockAdminRequest request) {
        return Result.ok(adminService.update(id, request));
    }

    @PostMapping("/batch-status")
    public Result<Integer> batchStatus(@RequestBody BStockBatchStatusRequest request) {
        return Result.ok(adminService.batchStatus(request.getIds(), request.getCatalogStatus()));
    }

    @PostMapping("/{id}/regenerate-alias")
    public Result<BStockAdminDTO> regenerateAlias(@PathVariable Long id) {
        return Result.ok(adminService.regenerateAlias(id));
    }

    @PostMapping("/sync")
    public Result<BStockCatalogSyncResult> sync() {
        return Result.ok(adminService.syncNow());
    }
}
