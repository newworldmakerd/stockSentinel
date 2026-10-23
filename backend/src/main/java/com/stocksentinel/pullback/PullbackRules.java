package com.stocksentinel.pullback;

import com.stocksentinel.common.entity.MarketDailyBar;
import com.stocksentinel.strategy.engine.*;
import java.math.*;
import java.util.*;

/** Input bars are chronological, final QFQ bars already checked by StrategyContextReader. */
public final class PullbackRules {
    private PullbackRules() {}
    public record Decision(boolean matched,List<RuleResult> rules) {}
    public static Decision evaluate(List<MarketDailyBar> bars,StrategyContext c,PullbackSettings p) {
        if(bars.size()<27 || !bars.getLast().tradeDate.equals(c.currentBar().tradeDate))return new Decision(false,List.of());
        int end=bars.size()-1,days=0;
        while(end-days>0 && bars.get(end-days).closePrice.compareTo(bars.get(end-days-1).closePrice)<0)days++;
        int anchor=end-days;
        if(days<p.minDays() || days>p.maxDays() || anchor<20)return new Decision(false,List.of());
        var anchorClose=bars.get(anchor).closePrice;var base=bars.get(anchor-20).closePrice;
        var high=bars.subList(anchor-19,anchor+1).stream().map(b->b.highPrice).max(BigDecimal::compareTo).orElseThrow();
        var close=bars.getLast().closePrice;
        var drop=high.subtract(close).multiply(new BigDecimal("100"));
        var pull=bars.subList(anchor+1,end+1).stream().map(b->b.volume).reduce(BigDecimal.ZERO,BigDecimal::add);
        var prior=bars.subList(anchor-4,anchor+1).stream().map(b->b.volume).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean positive=bars.subList(anchor-4,end+1).stream().allMatch(b->b.volume.signum()>0);
        var rules=List.of(
            rule(anchorClose.subtract(base).multiply(new BigDecimal("100")).compareTo(base.multiply(p.risePct()))>=0,"PREVIOUS_RISE",pct(anchorClose.subtract(base),base),p.risePct()+"%","回踩前20交易日涨幅"),
            rule(true,"PULLBACK_DAYS",String.valueOf(days),p.minDays()+"～"+p.maxDays(),"连续收盘回调天数"),
            rule(drop.compareTo(high.multiply(p.minDrawdownPct()))>=0 && drop.compareTo(high.multiply(p.maxDrawdownPct()))<=0,"DRAWDOWN",pct(high.subtract(close),high),p.minDrawdownPct()+"%～"+p.maxDrawdownPct()+"%","距回踩前20日最高价回撤"),
            rule(positive && pull.multiply(new BigDecimal("5")).compareTo(prior.multiply(BigDecimal.valueOf(days)).multiply(p.contraction()))<=0,"VOLUME_CONTRACTION",prior.signum()>0?pull.multiply(new BigDecimal("5")).divide(prior.multiply(BigDecimal.valueOf(days)),4,RoundingMode.HALF_UP).toPlainString():"无有效量",p.contraction().toPlainString(),"回调均量/回调前5日均量"),
            rule(c.current().ma20.compareTo(c.previous().ma20)>0,"MA20_RISING",c.current().ma20.toPlainString(),c.previous().ma20.toPlainString(),"MA20向上"),
            rule(close.compareTo(c.current().ma20)>0,"ABOVE_MA20",close.toPlainString(),c.current().ma20.toPlainString(),"前复权收盘仍在MA20上方"));
        return new Decision(rules.stream().allMatch(RuleResult::passed),rules);
    }
    private static String pct(BigDecimal n,BigDecimal d){return n.multiply(new BigDecimal("100")).divide(d,4,RoundingMode.HALF_UP)+"%";}
    private static RuleResult rule(boolean pass,String code,String observed,String threshold,String text){return new RuleResult(pass,code,observed,threshold,text);}
}
