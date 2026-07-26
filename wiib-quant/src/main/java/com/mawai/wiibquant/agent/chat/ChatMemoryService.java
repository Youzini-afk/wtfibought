package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.dto.WorkbenchMemoryEntry;
import com.mawai.wiibquant.mapper.WorkbenchMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作台跨会话长期记忆（P5）：写入规则化（不烧 LLM）——对话中出现的关注 symbol 计数 +
 * 最近一次问题/回答摘要；召回拼成 prompt 前缀段，让 agent 记得"用户常看什么、上次聊到哪"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private static final int SUMMARY_MAX = 200;
    private static final int RECALL_LIMIT = 5;

    private final WorkbenchMemoryMapper memoryMapper;

    /** 对话完成后规则化提取写入（异步调用方保证不阻塞 SSE 收尾）。 */
    public void remember(long userId, String question, String answer) {
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            String coin = symbol.replace("USDT", "");
            if (!question.toUpperCase().contains(coin)) {
                continue;
            }
            try {
                memoryMapper.upsert(userId, symbol, truncate(question), truncate(answer));
            } catch (Exception e) {
                // 记忆是增益不是主链，失败只记日志；单个 symbol 失败不影响其余
                log.warn("[Memory] 写入失败 userId={} symbol={}", userId, symbol, e);
            }
        }
    }

    /** 召回：拼成注入对话的前缀段；无记忆返回空串。 */
    public String recall(long userId) {
        List<WorkbenchMemoryEntry> entries;
        try {
            entries = memoryMapper.selectRecent(userId, RECALL_LIMIT);
        } catch (Exception e) {
            log.warn("[Memory] 召回失败 userId={}", userId, e);
            return "";
        }
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【用户历史偏好（跨会话记忆）】\n");
        for (WorkbenchMemoryEntry entry : entries) {
            sb.append("- ").append(entry.getSymbol()).append(" 关注").append(entry.getHitCount()).append("次");
            if (entry.getLastQuestion() != null) {
                sb.append("，上次问：").append(entry.getLastQuestion());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX) + "…" : s;
    }
}
