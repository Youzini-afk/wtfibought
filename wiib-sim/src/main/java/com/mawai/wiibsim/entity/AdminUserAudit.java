package com.mawai.wiibsim.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户管理写操作审计，不记录密码、Token 或请求正文。 */
@Data
@TableName("admin_user_audit")
public class AdminUserAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long operatorUserId;
    private Long targetUserId;
    private String action;
    private String beforeValue;
    private String afterValue;
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
