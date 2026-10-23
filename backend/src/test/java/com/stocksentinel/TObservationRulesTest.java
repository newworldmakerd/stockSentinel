package com.stocksentinel;

import com.stocksentinel.personal.*;
import com.stocksentinel.commands.TObservationIntent;
import java.time.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TObservationRulesTest {
    final OffsetDateTime now=OffsetDateTime.parse("2026-10-21T10:21:05+08:00");
    TObservationRules.Rsi rsi(String five,String fifteen){return new TObservationRules.Rsi("000731","AKSHARE_EASTMONEY_5M","QFQ",14,now,now.withMinute(20).withSecond(0),now.withMinute(15).withSecond(0),new BigDecimal(five),new BigDecimal(fifteen));}
    @Test void resonanceUsesStrictThresholdsAndBothPeriods(){
        assertEquals("SELL",TObservationRules.action(rsi("81","51")));assertEquals("BUY",TObservationRules.action(rsi("19","49")));
        for(var r:java.util.List.of(rsi("80","51"),rsi("19","50"),rsi("81","49"),rsi("20","49")))assertEquals("WAIT",TObservationRules.action(r));
    }
    @Test void timeAndDataBoundaries(){
        assertFalse(TObservationRules.entryTime(LocalTime.of(9,59)));assertTrue(TObservationRules.entryTime(LocalTime.of(10,0)));assertFalse(TObservationRules.entryTime(LocalTime.of(12,0)));
        assertTrue(TObservationRules.entryTime(LocalTime.of(14,49)));assertFalse(TObservationRules.entryTime(LocalTime.of(14,50)));
        assertTrue(TObservationRules.fresh("000731",rsi("81","51"),now));assertFalse(TObservationRules.fresh("600487",rsi("81","51"),now));
        assertFalse(TObservationRules.fresh("000731",rsi("81","51"),now.plusMinutes(7)));
        assertFalse(TObservationRules.fresh("000731",rsi("101","51"),now));
    }
    @Test void holdingsRequireTodaysConfirmedSellableAndLiteralReceiptCannotBeInvented(){
        var p=new PositionMapper.Position();p.quantity=800L;p.sellableQuantity=800L;p.reportedOn=now.toLocalDate();assertTrue(TObservationService.ready(p,now.toLocalDate()));
        p.reportedOn=p.reportedOn.minusDays(1);assertFalse(TObservationService.ready(p,now.toLocalDate()));p.reportedOn=now.toLocalDate();p.sellableQuantity=null;assertFalse(TObservationService.ready(p,now.toLocalDate()));
        String command="记录四川美丰做T卖出成交200股，成交价8元";
        assertTrue(TObservationIntent.explicit(command,"四川美丰","SELL",200,new BigDecimal("8")));
        for(String text:java.util.List.of("不要"+command,"如果"+command,command+"吗",command+"，替张三",command+"，再买另一只"))assertFalse(TObservationIntent.explicit(text,"四川美丰","SELL",200,new BigDecimal("8")));
        assertFalse(TObservationIntent.explicit(command,"四川美丰","SELL",300,new BigDecimal("8")));
        assertTrue(TObservationIntent.amendment("更正做T成交123为100股，成交价8元",123,false,100L,new BigDecimal("8")));
        assertTrue(TObservationIntent.amendment("撤销做T成交123",123,true,null,null));
        assertFalse(TObservationIntent.amendment("不要撤销做T成交123",123,true,null,null));
        assertFalse(TObservationIntent.amendment("更正做T成交123为100股，成交价8元",123,false,200L,new BigDecimal("8")));
    }
}
