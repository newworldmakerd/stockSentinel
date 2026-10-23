package com.stocksentinel.intraday;

import com.stocksentinel.intraday.IntradayContracts.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Ma60RiskRulesTest {
    static final OffsetDateTime AT=OffsetDateTime.parse("2026-10-21T10:20:00+08:00");
    static Bar bar(OffsetDateTime at,String price){var n=new BigDecimal(price);return new Bar(at,n,n,n,n,BigDecimal.ONE,BigDecimal.ONE);}
    static Snapshot snapshot(OffsetDateTime before,String p,String c){return new Snapshot("600487","FIXTURE","NONE","SHARE","CNY",AT.plusMinutes(1),List.of(bar(before,p),bar(AT,c)),null);}
    @Test void onlyDownwardCrossFromAboveOrEqualQualifies(){
        assertTrue(Ma60RiskService.crossed(snapshot(AT.minusMinutes(1),"100","99"),new BigDecimal("100")));
        assertTrue(Ma60RiskService.crossed(snapshot(AT.minusMinutes(1),"101","99"),new BigDecimal("100")));
        assertFalse(Ma60RiskService.crossed(snapshot(AT.minusMinutes(1),"99","98"),new BigDecimal("100")));
        assertFalse(Ma60RiskService.crossed(snapshot(AT.minusMinutes(1),"101","100"),new BigDecimal("100")));
        assertFalse(Ma60RiskService.crossed(snapshot(AT.minusMinutes(1),"99","101"),new BigDecimal("100")));
    }
    @Test void minuteGapAndLunchGapCannotGenerateCross(){
        assertFalse(Ma60RiskService.crossed(snapshot(AT.minusMinutes(2),"101","99"),new BigDecimal("100")));
        var afternoon=AT.withHour(13).withMinute(0);
        var s=new Snapshot("600487","FIXTURE","NONE","SHARE","CNY",afternoon.plusMinutes(1),
            List.of(bar(AT.withHour(11).withMinute(30),"101"),bar(afternoon,"99")),null);
        assertFalse(Ma60RiskService.crossed(s,new BigDecimal("100")));
    }
    @Test void missingMixedOrStaleDailyBasisIsRejected(){
        assertEquals("MA60_DAILY_BASIS_MISSING",Ma60RiskService.validate(null));
        var b=new Ma60RiskMapper.Basis();b.ma60=new BigDecimal("25");b.qfqClose=new BigDecimal("25");b.referenceClose=new BigDecimal("100");
        b.historyCount=120L;b.sourceCount=1L;b.source="fixture";b.referenceSource="fixture";b.lastFetched=AT;b.calculatedAt=AT;
        assertNull(Ma60RiskService.validate(b));b.referenceSource="other";assertEquals("MA60_MIXED_DAILY_SOURCE",Ma60RiskService.validate(b));
        b.referenceSource=b.source;b.calculatedAt=AT.minusSeconds(1);assertEquals("MA60_STALE_DAILY_INDICATOR",Ma60RiskService.validate(b));
        assertThrows(IllegalArgumentException.class,()->new Ma60RiskSettings(true,0));
    }
    @Test void disabledRiskDoesNotReadDataOrWriteSignals(){
        var service=new Ma60RiskService(null,null,null,null,null,new Ma60RiskSettings(false,15),null,null,null);
        assertTrue(service.evaluate(null,null).isEmpty());
    }
}
