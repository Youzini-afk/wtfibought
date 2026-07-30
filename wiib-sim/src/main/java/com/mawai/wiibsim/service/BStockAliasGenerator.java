package com.mawai.wiibsim.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * 影子股票展示身份生成器。
 *
 * <p>算法只负责首次生成候选，结果会写入数据库。之后即使算法升级，既有资产也不会
 * 静默改名；只有管理员明确点击“重新生成”才会采用新版本。</p>
 */
@Component
public class BStockAliasGenerator {

    public static final int VERSION = 1;
    private static final String CODE_ALPHABET = "ZXQVKJMWYF";

    /** 少量标志性名字负责定调，不承担目录覆盖；未知新股仍走通用规则。 */
    private static final Map<String, String> ICONIC_NAMES = Map.ofEntries(
            Map.entry("NVDA", "英伟呆"),
            Map.entry("TSLA", "特撕拉"),
            Map.entry("AAPL", "苹锅"),
            Map.entry("GOOGL", "咕歌"),
            Map.entry("MSFT", "薇软"),
            Map.entry("AMZN", "亚码逊"),
            Map.entry("BABA", "阿狸巴巴"),
            Map.entry("ORCL", "假骨文"),
            Map.entry("QCOM", "糕通"),
            Map.entry("DELL", "呆尔"),
            Map.entry("MU", "美光科幻"),
            Map.entry("SNDK", "闪递"),
            Map.entry("SPCX", "太空叉"),
            Map.entry("QQQ", "纳指100影子ETF"),
            Map.entry("SPY", "标普500影子ETF"),
            Map.entry("SOXL", "半导体3x影子ETF"),
            Map.entry("SOXS", "半导体3x反向影子ETF"),
            Map.entry("TQQQ", "纳指3x影子ETF")
    );

    /** 有中文公司名时只换一个字；匹配不到则轻量追加“影”，不乱改金融术语。 */
    private static final Map<Character, Character> ONE_CHAR_PUNS = new LinkedHashMap<>();

    static {
        ONE_CHAR_PUNS.put('果', '锅');
        ONE_CHAR_PUNS.put('谷', '咕');
        ONE_CHAR_PUNS.put('微', '薇');
        ONE_CHAR_PUNS.put('光', '慌');
        ONE_CHAR_PUNS.put('通', '桶');
        ONE_CHAR_PUNS.put('甲', '假');
        ONE_CHAR_PUNS.put('斯', '撕');
        ONE_CHAR_PUNS.put('马', '码');
        ONE_CHAR_PUNS.put('里', '狸');
        ONE_CHAR_PUNS.put('戴', '呆');
        ONE_CHAR_PUNS.put('星', '猩');
        ONE_CHAR_PUNS.put('盛', '剩');
        ONE_CHAR_PUNS.put('台', '苔');
        ONE_CHAR_PUNS.put('联', '莲');
        ONE_CHAR_PUNS.put('云', '芸');
    }

    public Alias generate(String ticker, String realName, String industry) {
        String normalizedTicker = normalizeTicker(ticker);
        String displayCode = generateCode(normalizedTicker);
        String displayName = ICONIC_NAMES.get(normalizedTicker);
        if (displayName == null) {
            displayName = lightlyFictionalize(realName, displayCode);
        }
        String category = industry == null || industry.isBlank() ? "市场" : industry.trim();
        String lore = "来自平行行情宇宙的" + category + "标的；价格与交易规则仍按真实行情计算。";
        return new Alias(displayName, displayCode, truncate(lore, 255), VERSION);
    }

    private String lightlyFictionalize(String realName, String displayCode) {
        String name = realName == null ? "" : realName.trim();
        if (name.isEmpty()) return displayCode + "影";

        for (Map.Entry<Character, Character> entry : ONE_CHAR_PUNS.entrySet()) {
            int index = name.indexOf(entry.getKey());
            if (index >= 0) {
                return truncate(name.substring(0, index) + entry.getValue() + name.substring(index + 1), 64);
            }
        }
        if (name.toUpperCase(Locale.ROOT).endsWith("ETF")) {
            return truncate(name.substring(0, name.length() - 3) + "影子ETF", 64);
        }
        return truncate(name + "影", 64);
    }

    private String generateCode(String ticker) {
        CRC32 crc = new CRC32();
        crc.update(ticker.getBytes(StandardCharsets.UTF_8));
        long value = crc.getValue();
        char a = CODE_ALPHABET.charAt((int) (value % CODE_ALPHABET.length()));
        char b = CODE_ALPHABET.charAt((int) ((value / CODE_ALPHABET.length()) % CODE_ALPHABET.length()));
        if (ticker.length() <= 2) return ticker + a;
        return ticker.substring(0, ticker.length() - 2) + a + b;
    }

    private String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) return "SHDW";
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Alias(String displayName, String displayCode, String displayLore, int version) {}
}
