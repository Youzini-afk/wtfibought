package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("external_quota_transfer")
public class ExternalQuotaTransfer {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String operationId;
    private Long userId;
    private Long newApiUserId;
    private String direction;
    private BigDecimal amount;
    private Long quotaAmount;
    private String status;
    private String remoteStatus;
    private String errorCode;
    private String errorMessage;
    private Long remoteQuotaAfter;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
