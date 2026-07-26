package com.mawai.wiibquant.mapper;

import com.mawai.wiibcommon.dto.WorkbenchMemoryEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作台跨会话长期记忆读写：{@code (user_id, symbol)} 复合主键一张表，就两个操作。
 * <p>
 * 刻意不 extends BaseMapper——它那套 selectById/updateById 认单一 @TableId，
 * 对复合主键本就用不上，继承进来只是摆着，还给人"能按 id 查"的错觉。
 */
@Mapper
public interface WorkbenchMemoryMapper {

    /**
     * 记住一次关注。冲突时只累加计数并覆盖最近问答，首次写入时间不动。
     * <p>
     * 计数走 SQL 原子自增而非先读后写：并发问同一 symbol 时后者会丢计数。
     */
    @Insert("""
            INSERT INTO workbench_memory
                (user_id, symbol, hit_count, last_question, last_answer_summary, updated_at)
            VALUES (#{userId}, #{symbol}, 1, #{question}, #{answerSummary}, now())
            ON CONFLICT (user_id, symbol) DO UPDATE SET
                hit_count           = workbench_memory.hit_count + 1,
                last_question       = EXCLUDED.last_question,
                last_answer_summary = EXCLUDED.last_answer_summary,
                updated_at          = now()
            """)
    int upsert(@Param("userId") long userId, @Param("symbol") String symbol,
               @Param("question") String question, @Param("answerSummary") String answerSummary);

    /** 召回最近关注的几个 symbol，拼 prompt 前缀用。摘要列不查——只进库不出库。 */
    @Select("""
            SELECT symbol,
                   hit_count     AS hitCount,
                   last_question AS lastQuestion
              FROM workbench_memory
             WHERE user_id = #{userId}
             ORDER BY updated_at DESC
             LIMIT #{limit}
            """)
    List<WorkbenchMemoryEntry> selectRecent(@Param("userId") long userId, @Param("limit") int limit);
}
