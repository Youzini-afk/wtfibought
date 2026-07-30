package com.mawai.wiibsim.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/** 管理员按需调用的影子股票架空命名器；目录自动同步仍使用零成本确定性规则。 */
@Service
@RequiredArgsConstructor
public class BStockAliasLlmService {

    private final AiService aiService;

    public BStockAliasGenerator.Alias generate(BStock stock) {
        String basePrompt = prompt(stock);
        BizException lastInvalid = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String requestPrompt = attempt == 1 ? basePrompt : basePrompt
                    + "\n上一次输出未通过格式或脱敏校验。请重新创作，并严格只返回满足约束的 JSON。";
            String response;
            try {
                response = aiService.chatFor(AiFunctions.BSTOCK_ALIAS, requestPrompt, 0.8);
            } catch (Exception e) {
                throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(),
                        "架空名LLM调用失败：" + rootMessage(e));
            }
            try {
                return parseAndValidate(stock, response);
            } catch (BizException e) {
                lastInvalid = e;
            } catch (Exception e) {
                lastInvalid = new BizException(ErrorCode.SYSTEM_ERROR.getCode(),
                        "架空名LLM返回格式无效，原别名未修改：" + rootMessage(e));
            }
        }
        throw lastInvalid == null
                ? new BizException(ErrorCode.SYSTEM_ERROR.getCode(), "架空名LLM未返回有效结果，原别名未修改")
                : lastInvalid;
    }

    private BStockAliasGenerator.Alias parseAndValidate(BStock stock, String response) {
        JSONObject json = JSON.parseObject(JsonUtils.extractJson(response));
        String displayName = required(json.getString("displayName"), "displayName", 32);
        String displayCode = required(json.getString("displayCode"), "displayCode", 16)
                .toUpperCase(Locale.ROOT);
        String displayLore = required(json.getString("displayLore"), "displayLore", 255);
        validate(stock, displayName, displayCode, displayLore);
        return new BStockAliasGenerator.Alias(
                displayName, displayCode, displayLore, BStockAliasGenerator.VERSION);
    }

    private String prompt(BStock stock) {
        return """
                你是一个轻松幽默的架空证券市场命名编辑。请为下面的现实原型创作一套新的“影子股票”身份。

                现实原型只用于理解行业气质，绝不能在输出中照抄或近似复刻公司名、ticker、产品名；
                名字要像自然存在于平行世界的公司，不要机械地在原名后追加“影”“虚拟”“平行”等字。
                可以有一两字的谐趣，但不要低俗，也不要给投资建议。

                原型 ticker：%s
                原型名称：%s
                行业：%s
                当前架空名：%s
                当前架空代码：%s

                只返回一个 JSON 对象，不要代码块、解释或额外文本：
                {"displayName":"2-16字中文架空公司名","displayCode":"2-8位大写英文字母或数字，不能等于原ticker","displayLore":"20-100字中文世界观简介，不得出现现实原型名或ticker"}
                新结果应与当前架空名或当前架空代码至少有一项不同。
                """.formatted(
                safe(stock.getTicker()), safe(stock.getName()), safe(stock.getIndustry()),
                safe(stock.getDisplayName()), safe(stock.getDisplayCode()));
    }

    private void validate(BStock stock, String name, String code, String lore) {
        if (!code.matches("[A-Z0-9._-]{2,16}")) {
            throw invalid("displayCode只能包含2-16位大写字母、数字、点、下划线或短横线");
        }
        String ticker = safe(stock.getTicker()).trim().toUpperCase(Locale.ROOT);
        if (!ticker.isEmpty() && code.equals(ticker)) {
            throw invalid("displayCode不能等于现实ticker");
        }
        String realName = compact(stock.getName());
        String combinedText = compact(name + lore);
        if (realName.length() >= 3 && combinedText.contains(realName)) {
            throw invalid("输出泄露了现实原型名称");
        }
        if (!ticker.isEmpty() && containsTickerToken(name + " " + lore, ticker)) {
            throw invalid("displayName或displayLore泄露了现实ticker");
        }
        if (name.equalsIgnoreCase(safe(stock.getName()).trim()) || name.equalsIgnoreCase(ticker)) {
            throw invalid("displayName不能等于现实原型");
        }
        boolean nameUnchanged = name.equals(safe(stock.getDisplayName()).trim());
        boolean codeUnchanged = code.equalsIgnoreCase(safe(stock.getDisplayCode()).trim());
        if (nameUnchanged && codeUnchanged) {
            throw invalid("模型返回了与当前完全相同的别名");
        }
    }

    private static String required(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw invalid(field + "为空或超过" + max + "字");
        }
        return normalized;
    }

    private static BizException invalid(String detail) {
        return new BizException(ErrorCode.SYSTEM_ERROR.getCode(),
                "架空名LLM返回内容不合规，原别名未修改：" + detail);
    }

    private static String compact(String value) {
        return safe(value).replaceAll("[\\s\\p{Punct}]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean containsTickerToken(String value, String ticker) {
        return Pattern.compile("(?i)(?<![A-Z0-9])" + Pattern.quote(ticker) + "(?![A-Z0-9])")
                .matcher(safe(value)).find();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
