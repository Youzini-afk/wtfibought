package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.dto.CommentDTO;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.util.UserSessionAccess;
import com.mawai.wiibsim.dto.AdminUserUpdateRequest;
import com.mawai.wiibsim.service.AdminUserService;
import com.mawai.wiibsim.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 留言板接口。整体需登录：路径不在 {@code SaTokenConfig} 放行清单里，拦截器先挡一道，
 * 各接口的 {@code @CurrentUserId} / {@code @RequireAdmin} 再取一次 loginId。
 */
@Tag(name = "留言板")
@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final AdminUserService adminUserService;

    @Data
    public static class PostRequest {
        private String content;
        /** 回复时传所属根评论ID；发根评论传 null */
        private Long rootId;
        /** 回复时传被回复者，用于展示"回复 @xxx" */
        private Long replyToUserId;
    }

    @Data
    public static class EditRequest {
        private String content;
    }

    @Data
    public static class MuteRequest {
        private Long userId;
        /** 禁言天数：-1=永久，0=立即解禁 */
        private Integer days;
        /** 可选管理原因；旧前端未传时使用留言板管理作为审计原因 */
        private String reason;
    }

    @GetMapping
    @Operation(summary = "根评论分页（时间倒序，带子评论预览与本人表态）")
    public Result<List<CommentDTO>> list(@CurrentUserId Long userId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return Result.ok(commentService.listRoots(userId, page, size));
    }

    @GetMapping("/{rootId}/children")
    @Operation(summary = "子评论分页")
    public Result<List<CommentDTO>> children(@CurrentUserId Long userId,
                                             @PathVariable long rootId,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return Result.ok(commentService.listChildren(userId, rootId, page, size));
    }

    @GetMapping("/context/{commentId}")
    @Operation(summary = "聚焦视图：该评论所属根评论+其全部子评论（通知跳转用）")
    public Result<CommentDTO> context(@CurrentUserId Long userId, @PathVariable long commentId) {
        return Result.ok(commentService.context(userId, commentId));
    }

    @PostMapping
    @Operation(summary = "发表评论或回复")
    public Result<Long> post(@CurrentUserId Long userId, @RequestBody PostRequest req) {
        return Result.ok(commentService.post(userId, req.getContent(),
                req.getRootId(), req.getReplyToUserId()).getId());
    }

    @PostMapping("/{id}/vote")
    @Operation(summary = "赞或踩（一人对一条只能表态一次）")
    public Result<Void> vote(@CurrentUserId Long userId, @PathVariable long id,
                             @RequestParam String type) {
        // 不认识的 type 必须拒掉：默认当成踩的话，前端一个拼写错误就把赞变成了踩
        boolean like = "like".equals(type);
        if (!like && !"dislike".equals(type)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        commentService.vote(userId, id, like);
        return Result.ok(null);
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑自己的评论")
    public Result<Void> edit(@CurrentUserId Long userId, @PathVariable long id,
                             @RequestBody EditRequest req) {
        commentService.edit(id, userId, req.getContent());
        return Result.ok(null);
    }

    /** 删自己的变占位文案，管理员删别人的才真删——分流在 Service 里，接口签名不变 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除评论（本人或管理员）")
    public Result<Void> delete(@CurrentUserId Long userId, @PathVariable long id) {
        commentService.delete(id, userId, UserSessionAccess.currentIsAdmin());
        return Result.ok(null);
    }

    @PostMapping("/mute")
    @RequireAdmin
    @Operation(summary = "禁言用户（管理员）")
    public Result<Void> mute(@CurrentUserId Long operatorId, @RequestBody MuteRequest req) {
        if (req.getUserId() == null || req.getDays() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        AdminUserUpdateRequest update = new AdminUserUpdateRequest();
        update.setMuteDays(req.getDays());
        update.setReason(req.getReason() == null || req.getReason().isBlank()
                ? "留言板管理" : req.getReason());
        adminUserService.update(operatorId, req.getUserId(), update);
        return Result.ok(null);
    }
}
