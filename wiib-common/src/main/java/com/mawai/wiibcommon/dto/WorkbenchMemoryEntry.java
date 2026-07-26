package com.mawai.wiibcommon.dto;

import lombok.Data;

/** 一条工作台跨会话记忆：用户关注过哪个 symbol、关注过几次、上次问了什么。 */
@Data
public class WorkbenchMemoryEntry {

    private String symbol;

    private Long hitCount;

    private String lastQuestion;
}
