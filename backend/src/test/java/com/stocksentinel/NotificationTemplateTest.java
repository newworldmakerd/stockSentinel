package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.notification.*;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.advisory.Advice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationTemplateTest {
    @Test void intradayLayoutKeepsFactsWithoutTechnicalClutter() throws Exception {
        var s=signal();s.signalType="RAPID_RISE";s.strategyCode="INTRADAY_V1";s.strategyVersion="1.0.0-r1";
        s.signalAt=java.time.OffsetDateTime.parse("2026-10-22T01:35:00Z");
        s.reason="五分钟涨幅达到阈值（实际：2.196307094266；阈值：2）";
        var json=new ObjectMapper();
        String text=new NotificationTemplate(json).render(s,json.writeValueAsString(new Advice("等待回踩确认",List.of("不重复展开的模型依据"),List.of("可能回落","行情延迟","第三项风险"),"跌回触发位")),"亨通光电");
        assertTrue(text.contains("2026-10-22 09:35（北京时间）"));
        assertTrue(text.contains("实际：2.1963"));assertTrue(text.contains("阈值：2"));
        assertTrue(text.contains("触发原因"));assertTrue(text.contains("大模型辅助建议（摘要）"));
        assertTrue(text.indexOf("参考价格")<text.indexOf("等待回踩确认"));
        assertTrue(text.indexOf("等待回踩确认")<text.indexOf("触发原因"));
        assertTrue(text.indexOf("触发原因")<text.indexOf("AI风险"));
        assertTrue(text.contains("可能回落；行情延迟"));assertTrue(text.contains("其余风险未展开"));
        assertFalse(text.contains("不重复展开的模型依据"));
        assertFalse(text.contains("INTRADAY_V1"));assertFalse(text.contains("Asia/Shanghai"));
        assertFalse(text.contains("01:35"));assertEquals("1.0.0-r1",s.strategyVersion);
        assertTrue(s.reason.contains("2.196307094266"));
    }
    @Test void shortTimeHandlesCrossDateAndUnknownTime() {
        assertEquals("2026-10-22 00:01",MessageDisplay.time(java.time.OffsetDateTime.parse("2026-10-21T16:01:00Z")));
        assertEquals("未记录",MessageDisplay.time(null));
    }
    @Test void rendersChineseNameAlongsideCode(){
        var s=signal();s.symbol="600487";
        assertTrue(new NotificationTemplate(new ObjectMapper()).render(s,null,"亨通光电").contains("股票：亨通光电 600487"));
    }
    private SignalEvent signal() {
        var s=new SignalEvent();s.symbol="999997";s.tradeDate=LocalDate.of(2025,1,2);s.signalType="BUY_WATCH";
        s.strategyCode="SWING_TREND_V1";s.strategyVersion="1.0.0";s.referencePrice=BigDecimal.TEN;
        s.reason="固定规则依据";s.riskNote="固定风险";return s;
    }
    @Test void distinctSourcesAndUnadjustedReference() throws Exception {
        var json=new ObjectMapper();var template=new NotificationTemplate(json);
        String text=template.render(signal(),json.writeValueAsString(new Advice("固定建议",List.of("模型依据"),List.of("模型风险"),"固定失效")));
        assertTrue(text.contains("来源：本地确定性规则"));assertTrue(text.contains("来源：大模型辅助建议"));
        assertTrue(text.contains("不复权收盘价"));assertTrue(text.contains("不自动下单"));
    }
    @Test void utf8LimitAndNoBrokenSurrogatesOrInjectedMarkdown() throws Exception {
        var json=new ObjectMapper();var s=signal();s.reason="😀<@all>\n".repeat(500);
        var advice=new Advice("中".repeat(2000),List.of("中".repeat(2000)),List.of("中".repeat(2000)),"中".repeat(2000));
        String text=new NotificationTemplate(json).render(s,json.writeValueAsString(advice));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=4000);
        assertFalse(text.contains("<@all>"));assertFalse(text.contains("\uFFFD"));
        assertTrue(text.endsWith("不自动下单。"));
    }
    @Test void missingOrInvalidAdviceNeverHidesRuleSignal() {
        var template=new NotificationTemplate(new ObjectMapper());
        assertTrue(template.render(signal(),null).contains("暂未生成或未启用"));
        assertTrue(template.render(signal(),"invalid json").contains("保留上述规则信号"));
    }
    @Test void disabledSettingsNeedNoSecretsAndEnabledRequiresDestination() {
        assertDoesNotThrow(()->new NotificationSettings(false,"SMART_BOT","","",""));
        assertThrows(IllegalArgumentException.class,()->new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential",""));
        assertThrows(IllegalArgumentException.class,()->new NotificationSettings(true,"WEBHOOK","test-bot","test-only-credential","test-chat"));
    }
    @Test void invalidReferencePriceCannotBeSentAsRealObservation() {
        var s=signal();s.referencePrice=null;
        assertThrows(IllegalArgumentException.class,()->new NotificationTemplate(new ObjectMapper()).render(s,null));
    }
}
