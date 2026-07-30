package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BStockAliasLlmServiceTest {

    @Mock
    private AiService aiService;

    @InjectMocks
    private BStockAliasLlmService service;

    @Test
    void parsesAndValidatesDedicatedAliasResponse() {
        when(aiService.chatFor(eq(AiFunctions.BSTOCK_ALIAS), any(String.class), eq(0.8)))
                .thenReturn("```json\n{\"displayName\":\"橘猫算力工坊\",\"displayCode\":\"CAT7\","
                        + "\"displayLore\":\"一家在月背城经营云端算盘和发光芯片的古怪工坊。\"}\n```");

        BStockAliasGenerator.Alias alias = service.generate(stock());

        assertEquals("橘猫算力工坊", alias.displayName());
        assertEquals("CAT7", alias.displayCode());
        assertTrue(alias.displayLore().contains("月背城"));
    }

    @Test
    void rejectsRealityTickerAndKeepsFailureExplicit() {
        when(aiService.chatFor(eq(AiFunctions.BSTOCK_ALIAS), any(String.class), eq(0.8)))
                .thenReturn("{\"displayName\":\"橘猫工坊\",\"displayCode\":\"NVDA\","
                        + "\"displayLore\":\"这是一个完全架空的测试简介。\"}");

        BizException error = assertThrows(BizException.class, () -> service.generate(stock()));

        assertTrue(error.getMsg().contains("现实ticker"));
    }

    @Test
    void rejectsRealityTickerLeakedInLore() {
        when(aiService.chatFor(eq(AiFunctions.BSTOCK_ALIAS), any(String.class), eq(0.8)))
                .thenReturn("{\"displayName\":\"橘猫工坊\",\"displayCode\":\"CAT8\","
                        + "\"displayLore\":\"这家工坊秘密复刻 NVDA 的现实行情。\"}");

        BizException error = assertThrows(BizException.class, () -> service.generate(stock()));

        assertTrue(error.getMsg().contains("现实ticker"));
    }

    @Test
    void reportsDisabledFunctionWithoutRuleFallback() {
        when(aiService.chatFor(eq(AiFunctions.BSTOCK_ALIAS), any(String.class), eq(0.8)))
                .thenThrow(new RuntimeException("AI功能已关闭: bstock-alias"));

        BizException error = assertThrows(BizException.class, () -> service.generate(stock()));

        assertTrue(error.getMsg().contains("AI功能已关闭"));
    }

    @Test
    void retriesOneInvalidStructuredResponse() {
        when(aiService.chatFor(eq(AiFunctions.BSTOCK_ALIAS), any(String.class), eq(0.8)))
                .thenReturn("这不是JSON")
                .thenReturn("{\"displayName\":\"月背晶格局\",\"displayCode\":\"MOON8\","
                        + "\"displayLore\":\"月背城的晶格工匠联合经营一座会发光的算力交易大厅。\"}");

        BStockAliasGenerator.Alias alias = service.generate(stock());

        assertEquals("月背晶格局", alias.displayName());
        verify(aiService, times(2)).chatFor(eq(AiFunctions.BSTOCK_ALIAS), any(String.class), eq(0.8));
    }

    private static BStock stock() {
        BStock stock = new BStock();
        stock.setTicker("NVDA");
        stock.setName("NVIDIA Corporation");
        stock.setIndustry("Technology");
        stock.setDisplayName("英伟呆");
        stock.setDisplayCode("NVXX");
        return stock;
    }
}
