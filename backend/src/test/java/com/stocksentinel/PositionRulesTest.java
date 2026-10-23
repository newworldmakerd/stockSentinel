package com.stocksentinel;

import com.stocksentinel.personal.*;
import com.stocksentinel.common.entity.SignalEvent;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PositionRulesTest {
    final LocalDate day=LocalDate.of(2026,10,21);
    PositionMapper.Position position(){var p=new PositionMapper.Position();p.quantity=1000L;p.costPrice=new BigDecimal("50");p.sellableQuantity=500L;p.reportedOn=day;return p;}
    SignalEvent signal(String type){var s=new SignalEvent();s.signalType=type;s.tradeDate=day;s.referencePrice=new BigDecimal("55");return s;}
    @Test void existingHoldingDoesNotInventAdditionalBuyQuantity(){assertTrue(PositionService.note(position(),signal("BUY_WATCH"),day).contains("不等于需要追加买入"));}
    @Test void sellableLimitZeroUnknownAndCrossDayAreRespected(){
        var p=position();assertTrue(PositionService.note(p,signal("SELL_WATCH"),day).contains("可卖500股"));
        p.sellableQuantity=0L;assertTrue(PositionService.note(p,signal("RISK_ALERT"),day).contains("可卖0股"));
        p.sellableQuantity=null;assertTrue(PositionService.note(p,signal("STOP_LOSS"),day).contains("未知"));
        p.sellableQuantity=500L;p.reportedOn=day.minusDays(1);assertTrue(PositionService.note(p,signal("SELL_WATCH"),day).contains("跨日"));
    }
    @Test void emptyHoldingAndCostComparisonAreFactsNotOrders(){
        var p=position();assertTrue(PositionService.costComparison(p,signal("BUY_WATCH")).contains("+10.00%"));
        p.quantity=0L;p.costPrice=null;assertTrue(PositionService.note(p,signal("SELL_WATCH"),day).contains("无现有持股"));assertTrue(PositionService.note(p,signal("BUY_WATCH"),day).contains("不能推算买入股数"));
    }
}
