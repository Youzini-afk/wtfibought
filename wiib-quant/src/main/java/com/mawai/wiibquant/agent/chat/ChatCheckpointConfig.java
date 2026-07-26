package com.mawai.wiibquant.agent.chat;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.ai.chat.messages.Message;
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

    /**
     * 状态序列化器：主图、专家子图、saver 三处共用这一个，缺一处就出事。
     * <p>
     * 必须是 Jackson 版而非默认的 ObjectStreamStateSerializer：Spring AI 的 Message 全族
     * 不实现 Serializable，而 CompiledGraph 每存一次 checkpoint 都先按主图的序列化器 cloneState()，
     * 用 Java 对象流会当场 NotSerializableException。
     */
    @Bean
    public StateSerializer<MessagesState<Message>> workbenchStateSerializer() {
        return new SpringAIJacksonStateSerializer<>(MessagesState::new);
    }

    @Bean
    public BaseCheckpointSaver workbenchCheckpointSaver(
            DataSource dataSource, StateSerializer<MessagesState<Message>> stateSerializer) throws SQLException {
        return PostgresSaver.builder()
                .datasource(dataSource)
                .stateSerializer(stateSerializer)
                .createTables(true)
                .build();
    }
}
