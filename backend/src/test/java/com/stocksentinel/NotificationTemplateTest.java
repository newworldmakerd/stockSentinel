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
