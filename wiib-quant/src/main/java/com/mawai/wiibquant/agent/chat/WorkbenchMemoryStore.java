package com.mawai.wiibquant.agent.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台跨会话长期记忆存储：一张 (user_id, symbol) 主键表，就三个操作。
 * <p>
 * 为什么自己写：langgraph4j 没有 Store 抽象，而原先继承的 spring-ai-alibaba DatabaseStore
 * 也不能用——它的 putItem 是 H2 专有语法 {@code MERGE INTO ... KEY(id)}，PG 不认，
 * 写入从上线起一直静默失败（旧实现只能覆写 putItem 打补丁，表名/主键生成还得靠"复述"父类私有逻辑）。
 * <p>
 * 相比通用 Store 的改进：字段直接建列不塞 JSON；计数走 SQL 原子自增，
 * 免掉"先读后写"的竞态（并发同一 symbol 时旧实现会丢计数）。
 */
@Slf4j
@Component
public class WorkbenchMemoryStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS workbench_memory (
                user_id             BIGINT      NOT NULL,
                symbol              VARCHAR(32) NOT NULL,
                hit_count           BIGINT      NOT NULL DEFAULT 1,
                last_question       TEXT,
                last_answer_summary TEXT,
                updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (user_id, symbol)
            )""";

    /** 冲突时只累加计数并覆盖最近问答，首次写入时间不动 */
    private static final String UPSERT = """
            INSERT INTO workbench_memory (user_id, symbol, hit_count, last_question, last_answer_summary, updated_at)
            VALUES (?, ?, 1, ?, ?, now())
            ON CONFLICT (user_id, symbol) DO UPDATE SET
                hit_count           = workbench_memory.hit_count + 1,
                last_question       = EXCLUDED.last_question,
                last_answer_summary = EXCLUDED.last_answer_summary,
                updated_at          = now()""";

    private static final String SELECT_RECENT = """
            SELECT symbol, hit_count, last_question FROM workbench_memory
            WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?""";

    private final DataSource dataSource;

    public WorkbenchMemoryStore(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(DDL);
        }
    }

    /** 一条记忆：用户关注过哪个 symbol、关注过几次、上次问了什么。 */
    public record Entry(String symbol, long hitCount, String lastQuestion) {
    }

    public void remember(long userId, String symbol, String question, String answerSummary) throws SQLException {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setLong(1, userId);
            ps.setString(2, symbol);
            ps.setString(3, question);
            ps.setString(4, answerSummary);
            ps.executeUpdate();
        }
    }

    public List<Entry> recall(long userId, int limit) throws SQLException {
        List<Entry> entries = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(SELECT_RECENT)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getString("symbol"), rs.getLong("hit_count"), rs.getString("last_question")));
                }
            }
        }
        return entries;
    }
}
