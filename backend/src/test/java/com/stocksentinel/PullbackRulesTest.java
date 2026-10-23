package com.stocksentinel;

import com.stocksentinel.pullback.*;
import com.stocksentinel.common.entity.*;
import com.stocksentinel.strategy.engine.StrategyContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PullbackRulesTest {
    static BigDecimal n(String s){return new BigDecimal(s);}
    static PullbackSettings parameters(){return new PullbackSettings(true,n("15"),2,5,n("3"),n("8"),n("0.70"),n("1.5"),20);}
    static List<MarketDailyBar> bars(int days){
        var list=new ArrayList<MarketDailyBar>();
        for(int i=0;i<30+days;i++) {
            var b=new MarketDailyBar();b.tradeDate=LocalDate.of(2026,1,1).plusDays(i);
            b.closePrice=i<29?n("100"):i==29?n("120"):n("120").subtract(BigDecimal.valueOf(2L*(i-29)));
            b.highPrice=b.closePrice;b.volume=i<30?n("100"):n("70");list.add(b);
        }
        return list;
    }
    static StrategyContext context(List<MarketDailyBar> b){var c=new MarketIndicatorDaily();c.ma20=n("105");var p=new MarketIndicatorDaily();p.ma20=n("104");return new StrategyContext(b.getLast(),b.get(b.size()-2),b.getLast(),c,p,n("500"),null);}
    @Test void qualifiesTwoAndFiveDaysAndExactContraction(){for(int days:new int[]{2,5}){var b=bars(days);if(days==5)b.getLast().closePrice=n("111");assertTrue(PullbackRules.evaluate(b,context(b),parameters()).matched());}}
    @Test void rejectsOneAndSixDays(){for(int days:new int[]{1,6}){var b=bars(days);assertFalse(PullbackRules.evaluate(b,context(b),parameters()).matched());}}
    @Test void rejectsInsufficientMomentum(){var b=bars(2);b.get(9).closePrice=n("110");assertFalse(PullbackRules.evaluate(b,context(b),parameters()).matched());}
    @Test void rejectsTooMuchVolumeAndZeroVolume(){for(String volume:List.of("70.01","0")){var b=bars(2);b.getLast().volume=n(volume);assertFalse(PullbackRules.evaluate(b,context(b),parameters()).matched());}}
    @Test void rejectsFlatMaAndCloseBelowMa(){var b=bars(2);var c=context(b);c.current().ma20=c.previous().ma20;assertFalse(PullbackRules.evaluate(b,c,parameters()).matched());c.current().ma20=n("116");c.previous().ma20=n("115");assertFalse(PullbackRules.evaluate(b,c,parameters()).matched());}
    @Test void drawdownBoundariesAreInclusive(){for(String close:List.of("116.4","110.4")){var b=bars(2);b.getLast().closePrice=n(close);assertTrue(PullbackRules.evaluate(b,context(b),parameters()).matched());}for(String close:List.of("116.41","110.39")){var b=bars(2);b.getLast().closePrice=n(close);assertFalse(PullbackRules.evaluate(b,context(b),parameters()).matched());}}
    @Test void versionTracksRuleParametersAndValidatesBounds(){assertEquals(parameters().version(),parameters().version());assertThrows(IllegalArgumentException.class,()->new PullbackSettings(true,n("15"),2,5,n("3"),n("8"),n("0.70"),n("1.5"),0));}
}
