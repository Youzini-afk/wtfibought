package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.mawai.wiibcommon.dto.BStockAliasDTO;
import com.mawai.wiibcommon.dto.BStockDTO;
import com.mawai.wiibcommon.entity.BStock;

import java.math.BigDecimal;
import java.util.List;

/** bStock 读取服务：静态信息读 bstock 表，实时价/K线走 Binance（REST/Redis）。 */
public interface BStockService extends IService<BStock> {

    /** 全部上架 bStock（静态信息 + 实时价 + 24h 涨跌），按 sort 排序 */
    List<BStockDTO> listAll();

    /** 全局展示映射（含退役历史标的，不含候选；不暴露真实名称和 ticker）。 */
    List<BStockAliasDTO> listAliases();

    /** 单只详情（按现货符号，如 NVDABUSDT） */
    BStockDTO detail(String symbol);

    /** 最新价（Redis 优先，未命中回退 REST） */
    BigDecimal price(String symbol);

    /** 是否 bStock 现货符号（现货引擎据此判卖出瞬时结算 vs crypto 5min） */
    boolean isBStockSymbol(String symbol);

    /** 是否允许新开多仓；暂停/候选/退役或上游失联时为 false。 */
    boolean isBStockBuyAllowed(String symbol);

    /** 是否允许平仓；目录暂停/退役不影响，上游行情不可用时为 false。 */
    boolean isBStockSellAllowed(String symbol);

    /**
     * 在当前数据库事务内锁住目录行并判断买入。普通币种返回 true；
     * bStock 仅 LISTED + TRADING 返回 true。
     */
    boolean lockAndCheckBuyAllowed(String symbol);

    /** 在当前数据库事务内锁住目录行并判断卖出；普通币种直接返回 true。 */
    boolean lockAndCheckSellAllowed(String symbol);

    /** 目录被后台或同步任务修改后立即清理本地策略缓存。 */
    void invalidateCatalogCache();
}
