package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.mapper.Lg4jThreadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;

/**
 * 会话 checkpoint 的删除口：把"删掉一个会话的续聊上下文"这件事收在一处。
 * <p>
 * 为什么要两步：{@code saver.release()} 只把 lg4jthread 标成 is_released，state 数据仍留库，
 * 而"删会话"的语义是真删，所以还得按 thread_name 物理清一道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkbenchCheckpointStore {

    private final BaseCheckpointSaver checkpointSaver;
    private final Lg4jThreadMapper lg4jThreadMapper;

    /**
     * 清掉会话的 checkpoint 上下文。两步各自兜异常：这是尽力清，
     * 失败只影响存储占用，不该反过来让"列表里已删"这个用户观感落空。
     */
    public void purge(String sessionId) {
        try {
            checkpointSaver.release(RunnableConfig.builder().threadId(sessionId).build());
        } catch (IllegalStateException e) {
            // 会话没真跑通过图就没有 lg4jthread 行，release 无处着力——属正常不是故障
            log.debug("[Workbench] 会话无活跃 checkpoint 线程 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("[Workbench] checkpoint 释放失败 sessionId={} msg={}", sessionId, e.toString());
        }
        try {
            lg4jThreadMapper.deleteByThreadName(sessionId);
        } catch (Exception e) {
            log.warn("[Workbench] checkpoint 物理删除失败 sessionId={} msg={}", sessionId, e.toString());
        }
    }
}
