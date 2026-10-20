package com.stocksentinel;

import com.stocksentinel.intraday.*;
import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntradayRulesTest {
    @Test void missingAmountIsOnlyAcceptedForTencentAndDoesNotChangeRules() {
        var original=snapshot(new BigDecimal("102"),new BigDecimal("1500"));
        var bars=original.bars().stream().map(b->new Bar(b.barAt(),b.openPrice(),b.highPrice(),b.lowPrice(),b.closePrice(),b.volume(),null)).toList();
        var fallback=new Snapshot(original.symbol(),"TENCENT_1M",original.adjustment(),original.unit(),original.amountUnit(),original.observedAt(),bars,original.metrics());
        IntradayValidation.snapshot(original.symbol(),fallback,NOW);
        assertTrue(hit(config(),fallback,Type.RAPID_RISE));assertTrue(hit(config(),fallback,Type.BREAKOUT_WATCH));
        var primary=new Snapshot(original.symbol(),original.source(),original.adjustment(),original.unit(),original.amountUnit(),original.observedAt(),bars,original.metrics());
        assertThrows(IntradayValidation.Skip.class,()->IntradayValidation.snapshot(primary.symbol(),primary,NOW));
        assertThrows(IntradayValidation.Skip.class,()->IntradayValidation.snapshot(fallback.symbol(),fallback,NOW.plusMinutes(3)));
    }
    static final OffsetDateTime NOW=OffsetDateTime.of(2026,10,20,10,21,5,0,ZoneOffset.ofHours(8));
    static Config config() {
        var c=new Config();c.symbol="600487";c.enabled=true;c.rapidPct=new BigDecimal("2");c.volumeMultiplier=new BigDecimal("1.5");
        c.cooldownMinutes=15;c.revision=1L;return c;
    }
    static Snapshot snapshot(BigDecimal lastPrice,BigDecimal lastVolume) {
        var bars=new ArrayList<Bar>();
        for(int i=0;i<21;i++) {
            var price=i==20 ? lastPrice : new BigDecimal("100");
            bars.add(new Bar(NOW.withSecond(0).minusMinutes(21-i),price,price.add(new BigDecimal("0.1")),price.subtract(new BigDecimal("0.1")),price,
                i==20 ? lastVolume : new BigDecimal("1000"),new BigDecimal("100000")));
        }
        var pct=lastPrice.subtract(new BigDecimal("100"));
        return new Snapshot("600487","FIXTURE_MINUTE","NONE","SHARE","CNY",NOW,bars,new Metrics(pct,new BigDecimal("100.1"),new BigDecimal("1000")));
    }
    boolean hit(Config c,Snapshot s,Type type) {return new IntradayRules().evaluate(c,s).stream().filter(d->d.type()==type).findFirst().orElseThrow().matched();}
    @Test void exactTwoPercentAndVolumeBoundaries() {
        var c=config();var s=snapshot(new BigDecimal("102"),new BigDecimal("1500"));
        IntradayValidation.snapshot(c.symbol,s,NOW);assertTrue(hit(c,s,Type.RAPID_RISE));assertTrue(hit(c,s,Type.BREAKOUT_WATCH));
        assertFalse(hit(c,snapshot(new BigDecimal("101.9999"),new BigDecimal("1499")),Type.RAPID_RISE));
        assertFalse(hit(c,snapshot(new BigDecimal("102"),new BigDecimal("1499")),Type.BREAKOUT_WATCH));
    }
    @Test void rapidDropAndConfiguredPricesAreIndependent() {
        var c=config();c.takeProfit=new BigDecimal("103");c.stopLoss=new BigDecimal("98");
        var s=snapshot(new BigDecimal("98"),new BigDecimal("1000"));
        assertTrue(hit(c,s,Type.RAPID_DROP));assertTrue(hit(c,s,Type.STOP_LOSS));assertFalse(hit(c,s,Type.TAKE_PROFIT));
        assertTrue(hit(c,snapshot(new BigDecimal("103"),new BigDecimal("1000")),Type.TAKE_PROFIT));
        c.stopLoss=null;c.takeProfit=null;assertFalse(hit(c,s,Type.STOP_LOSS));assertFalse(hit(c,s,Type.TAKE_PROFIT));
    }
    @Test void staleDataAndForgedMetricsAreRejected() {
        var s=snapshot(new BigDecimal("102"),new BigDecimal("1500"));
        assertThrows(IntradayValidation.Skip.class,()->IntradayValidation.snapshot("600487",s,NOW.plusMinutes(3)));
        var invalid=new Snapshot(s.symbol(),s.source(),s.adjustment(),s.unit(),s.amountUnit(),s.observedAt(),s.bars(),new Metrics(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE));
        assertThrows(IntradayValidation.Skip.class,()->IntradayValidation.snapshot("600487",invalid,NOW));
    }
    @Test void lunchAndNightAreNotScanWindows() {
        assertFalse(IntradayValidation.scanTime(LocalTime.of(12,0)));assertFalse(IntradayValidation.scanTime(LocalTime.of(9,30)));
        assertFalse(IntradayValidation.scanTime(LocalTime.of(18,0)));assertTrue(IntradayValidation.scanTime(LocalTime.of(10,0)));
        assertTrue(IntradayValidation.scanTime(LocalTime.of(15,1)));assertFalse(IntradayValidation.scanTime(LocalTime.of(15,2)));
    }
    @Test void disabledScheduleNeverTouchesAnyService() {
        var scans=mock(IntradayScanService.class);new IntradayScheduler(scans,Clock.fixed(NOW.toInstant(),TradingDatePolicy.ZONE),false).tick();verifyNoInteractions(scans);
    }
    @Test void zeroCurrentVolumeDoesNotCreateFalseFreshQuote() {
        var data=snapshot(new BigDecimal("102"),BigDecimal.ZERO);
        assertEquals("NO_CURRENT_TRADES",assertThrows(IntradayValidation.Skip.class,()->IntradayValidation.snapshot("600487",data,NOW)).code);
    }
}
