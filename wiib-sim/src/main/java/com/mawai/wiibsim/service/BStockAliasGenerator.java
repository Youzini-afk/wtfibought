package com.mawai.wiibsim.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 影子股票展示身份生成器。
 *
 * <p>算法只负责首次生成候选，结果会写入数据库。之后即使算法升级，既有资产也不会
 * 静默改名；只有管理员明确点击“重新生成”才会采用新版本。</p>
 */
@Component
public class BStockAliasGenerator {

    public static final int VERSION = 2;
    private static final String CODE_ALPHABET = "ZXQVKJMWYF";
    private static final Set<String> FUND_TICKERS = Set.of(
            "DRAM", "EWY", "KORU", "QQQ", "SKHY", "SMH", "SOXL", "SOXS", "SPY", "TQQQ"
    );

    private static final String[] FICTION_PREFIXES = {
            "星穹", "雾港", "月背", "橘猫", "蓝鲸", "赤兔", "云雀", "黑曜",
            "白塔", "银狐", "铜锣", "松果", "萤火", "回声", "泡泡", "漩涡",
            "北斗", "南瓜", "纸鸢", "银河", "零号", "奇点", "风车", "深蓝",
            "琥珀", "乌托", "荒原", "幻岛", "夜航", "雨巷", "火花", "冰川"
    };

    /** 少量标志性名字负责定调，不承担目录覆盖；未知新股仍走通用规则。 */
    private static final Map<String, String> ICONIC_NAMES = Map.ofEntries(
            Map.entry("NVDA", "英伟呆"),
            Map.entry("TSLA", "特撕拉"),
            Map.entry("AAPL", "苹锅"),
            Map.entry("GOOGL", "咕歌"),
            Map.entry("MSFT", "薇软"),
            Map.entry("AMZN", "亚码逊"),
            Map.entry("BABA", "阿狸巴巴"),
            Map.entry("ARM", "胳膊芯"),
            Map.entry("AVGO", "博不通"),
            Map.entry("GS", "高剩集团"),
            Map.entry("HOOD", "罗宾帽"),
            Map.entry("IBM", "蓝色大象"),
            Map.entry("INTC", "因特慢"),
            Map.entry("META", "元它宇宙"),
            Map.entry("MRVL", "迈威尔奇"),
            Map.entry("PLTR", "帕兰提灯"),
            Map.entry("PYPL", "拍拍钱包"),
            Map.entry("TSM", "苔积电"),
            Map.entry("WDC", "西部数砖"),
            Map.entry("ORCL", "假骨文"),
            Map.entry("QCOM", "糕通"),
            Map.entry("DELL", "呆尔"),
            Map.entry("MU", "美光科幻"),
            Map.entry("SNDK", "闪递"),
            Map.entry("SPCX", "太空叉"),
            Map.entry("QQQ", "纳响100"),
            Map.entry("SPY", "标漂500"),
            Map.entry("SOXL", "芯潮三倍多"),
            Map.entry("SOXS", "芯潮三倍空"),
            Map.entry("TQQQ", "纳响三倍多")
    );

    public Alias generate(String ticker, String realName, String industry) {
        String normalizedTicker = normalizeTicker(ticker);
        String displayCode = generateCode(normalizedTicker);
        String displayName = ICONIC_NAMES.get(normalizedTicker);
        MarketTheme theme = MarketTheme.from(normalizedTicker, industry);
        if (displayName == null) {
            // 通用路径绝不复用真实公司名。未来新增股票也会稳定落入足够大的架空名字空间。
            displayName = proceduralName(normalizedTicker, theme);
        }
        String lore = "在平行行情宇宙登记的" + theme.label + "标的；名称与故事均为架空，价格只借用现实市场波动。";
        return new Alias(displayName, displayCode, truncate(lore, 255), VERSION);
    }

    private String proceduralName(String ticker, MarketTheme theme) {
        long value = stableValue("alias-name-v2|" + ticker);
        String prefix = FICTION_PREFIXES[(int) (value % FICTION_PREFIXES.length)];
        value /= FICTION_PREFIXES.length;
        String root = theme.roots[(int) (value % theme.roots.length)];
        value /= theme.roots.length;
        String form = theme.forms[(int) (value % theme.forms.length)];
        return truncate(prefix + root + form, 64);
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

    private long stableValue(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private enum MarketTheme {
        TECHNOLOGY("科技", new String[]{
                "算力", "晶格", "云端", "矩阵", "光栅", "芯火", "数据", "智械", "量子", "代码", "网格", "像素"
        }, new String[]{
                "实验室", "科技", "工坊", "研究所", "动力", "系统", "网络", "智造"
        }),
        FINANCE("金融", new String[]{
                "金流", "银潮", "账本", "票据", "利息", "复利", "钱币", "汇率", "铜板", "金桥", "信用", "资产"
        }, new String[]{
                "金库", "银号", "钱庄", "财团", "票号", "账房", "交易所", "钱包"
        }),
        CONSUMER("消费", new String[]{
                "日用", "百味", "好物", "橱窗", "买手", "零售", "货架", "糖果", "衣橱", "烟火", "市集", "订单"
        }, new String[]{
                "商店", "百货", "集市", "商社", "工坊", "超市", "便利屋", "商栈"
        }),
        COMMUNICATION("通信", new String[]{
                "讯号", "电波", "声场", "频段", "光纤", "铃声", "广播", "信使", "天线", "云邮", "频道", "话音"
        }, new String[]{
                "通讯社", "讯塔", "广播站", "信使局", "话务所", "信标", "电波局", "云邮"
        }),
        INDUSTRIAL("工业", new String[]{
                "齿轮", "蒸汽", "铆钉", "动力", "机巧", "重装", "航线", "炉火", "工程", "轨道", "机械", "运载"
        }, new String[]{
                "重工", "工程局", "机造", "动力厂", "航运社", "齿轮厂", "工业", "制造所"
        }),
        FUND("指数", new String[]{
                "指数", "权重", "波段", "多空", "复利", "篮子", "罗盘", "净值", "曲线", "因子", "趋势", "量潮"
        }, new String[]{
                "基金", "指数局", "风向标", "放大器", "组合", "观察站", "策略社", "研究所"
        }),
        MARKET("市场", new String[]{
                "未来", "幻想", "平行", "漂移", "概率", "时光", "星轨", "流星", "潮汐", "秘密", "边界", "引力"
        }, new String[]{
                "商会", "公社", "研究所", "联盟", "事务局", "观察站", "控股", "实验室"
        });

        private final String label;
        private final String[] roots;
        private final String[] forms;

        MarketTheme(String label, String[] roots, String[] forms) {
            this.label = label;
            this.roots = roots;
            this.forms = forms;
        }

        private static MarketTheme from(String ticker, String industry) {
            String value = industry == null ? "" : industry.trim().toLowerCase(Locale.ROOT);
            if (FUND_TICKERS.contains(ticker) || containsAny(value, "etf", "fund", "index", "基金", "指数")) return FUND;
            if (containsAny(value, "financial", "finance", "bank", "金融", "金库", "银行")) return FINANCE;
            if (containsAny(value, "communication", "telecom", "media", "通信", "通讯", "媒体")) return COMMUNICATION;
            if (containsAny(value, "consumer", "retail", "消费", "零售", "电商")) return CONSUMER;
            if (containsAny(value, "industrial", "aerospace", "manufactur", "工业", "航空", "制造")) return INDUSTRIAL;
            if (containsAny(value, "technology", "semiconductor", "software", "tech", "半导体", "科技", "软件", "数据")) return TECHNOLOGY;
            return MARKET;
        }

        private static boolean containsAny(String value, String... needles) {
            for (String needle : needles) if (value.contains(needle)) return true;
            return false;
        }
    }

    public record Alias(String displayName, String displayCode, String displayLore, int version) {}
}
