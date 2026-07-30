package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.mawai.wiibcommon.dto.CryptoOrderRequest;
import com.mawai.wiibcommon.dto.CryptoOrderResponse;
import com.mawai.wiibcommon.entity.CryptoOrder;

import java.math.BigDecimal;
import java.util.List;

public interface CryptoOrderService extends IService<CryptoOrder> {

    CryptoOrderResponse buy(Long userId, CryptoOrderRequest request);

    CryptoOrderResponse sell(Long userId, CryptoOrderRequest request);

    CryptoOrderResponse cancel(Long userId, Long orderId);

    IPage<CryptoOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol);

    /** 执行已触发的限价单 */
    void  executeTriggeredOrders();

    /** WS价格到达时检查限价单（事件驱动） */
    void onPriceUpdate(String symbol, BigDecimal price);

    /** 重启/重连后，根据期间高低价恢复触发限价单 */
    void recoverLimitOrders(String symbol, BigDecimal periodLow, BigDecimal periodHigh);

    /** 目录暂停、退役或上游不可用时，主动撤销并解冻该标的全部待成交买单。 */
    int cancelPendingBuys(String symbol);

    /** 上游行情不可用时，撤销待成交卖单并解冻持仓；普通目录暂停不调用。 */
    int cancelPendingSells(String symbol);

    /** 最新成交20条-匿名 */
    List<CryptoOrderResponse> getLatestOrders();
}
