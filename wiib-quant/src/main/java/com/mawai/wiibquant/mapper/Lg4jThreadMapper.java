package com.mawai.wiibquant.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * langgraph4j PostgresSaver 的内部线程表 lg4jthread。
 * <p>
 * 刻意不建 entity：表结构归框架所有、跟着依赖版本走，我们只借一条物理删除，
 * 不该把它当自家业务实体建模——建了 entity 就等于承诺跟着框架改表结构。
 */
@Mapper
public interface Lg4jThreadMapper {

    /**
     * 按 thread_name 物理删除会话线程。lg4jcheckpoint 对它有 ON DELETE CASCADE，
     * 删这一行 state 数据连坐清掉；行不存在则是无害空操作。
     */
    @Delete("DELETE FROM lg4jthread WHERE thread_name = #{threadName}")
    int deleteByThreadName(@Param("threadName") String threadName);
}
