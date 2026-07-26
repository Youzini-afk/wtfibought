package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作台会话列表的一行聚合结果（DB 口径）。
 * 标题是该会话首条用户消息的原文，截断留给 Service 做——SQL 里截会连省略号规则一起搬进 SQL。
 */
@Data
public class WorkbenchSessionRow {

    private String sessionId;

    private String title;

    private Integer messageCount;

    private LocalDateTime lastAt;
}
