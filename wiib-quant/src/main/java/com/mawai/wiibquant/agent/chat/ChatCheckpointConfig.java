package com.mawai.wiibquant.agent.chat;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 工作台会话 checkpoint：落主库 PG，threadId=sessionId，断连续聊与 HITL 中断恢复的地基。
 * <p>
 * 直接复用 Spring 的 DataSource（不必手拆 JDBC URL），建表 DDL 全带 IF NOT EXISTS 天然幂等，
 * 无需先探测表是否存在——这两点都是 spring-ai-alibaba 版缺的，迁到 langgraph4j 后一并省掉。
 */
@Configuration
public class ChatCheckpointConfig {

    @Bean
    public BaseCheckpointSaver workbenchCheckpointSaver(DataSource dataSource) throws SQLException {
        return PostgresSaver.builder()
                .datasource(dataSource)
                .createTables(true)
                .build();
    }
}
