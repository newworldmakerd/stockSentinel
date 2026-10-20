package com.stocksentinel.intraday;

import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.strategy.engine.RuleResult;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;

@Component
public class IntradayRules {
    public record Decision(Type type,boolean matched,List<RuleResult> rules) {}
    public List<Decision> evaluate(Config config,Snapshot data) {
        var latest=data.bars().getLast();var metrics=data.metrics();
        boolean fast=metrics.fiveMinuteReturnPct()!=null;
        BigDecimal base=fast ? data.bars().get(data.bars().size()-6).closePrice() : BigDecimal.ONE;
        BigDecimal move=latest.closePrice().subtract(base).multiply(BigDecimal.valueOf(100));
        boolean rise=fast && move.compareTo(base.multiply(config.rapidPct))>=0;
        boolean drop=fast && move.negate().compareTo(base.multiply(config.rapidPct))>=0;
        boolean enough=metrics.previousTwentyHigh()!=null && metrics.previousTwentyVolumeMean()!=null;
        boolean breakout=enough && latest.closePrice().compareTo(metrics.previousTwentyHigh())>0;
        boolean volume=enough && metrics.previousTwentyVolumeMean().signum()>0
            && latest.volume().compareTo(metrics.previousTwentyVolumeMean().multiply(config.volumeMultiplier))>=0;
        return List.of(one(Type.RAPID_RISE,rise,"FIVE_MINUTE_RISE",metrics.fiveMinuteReturnPct(),config.rapidPct,"五分钟涨幅达到阈值"),
            one(Type.RAPID_DROP,drop,"FIVE_MINUTE_DROP",metrics.fiveMinuteReturnPct(),config.rapidPct.negate(),"五分钟跌幅达到阈值"),
            new Decision(Type.BREAKOUT_WATCH,breakout && volume,List.of(
                rule(breakout,"BREAK_PREVIOUS_20M_HIGH",latest.closePrice(),metrics.previousTwentyHigh(),"收盘价严格突破此前20分钟最高价"),
                rule(volume,"VOLUME_CONFIRMATION",latest.volume(),enough ? metrics.previousTwentyVolumeMean().multiply(config.volumeMultiplier) : null,"分钟成交量达到此前20分钟均量的配置倍数"))),
            one(Type.TAKE_PROFIT,config.takeProfit!=null && latest.closePrice().compareTo(config.takeProfit)>=0,"TAKE_PROFIT_PRICE",latest.closePrice(),config.takeProfit,"达到用户配置的止盈提醒价"),
            one(Type.STOP_LOSS,config.stopLoss!=null && latest.closePrice().compareTo(config.stopLoss)<=0,"STOP_LOSS_PRICE",latest.closePrice(),config.stopLoss,"达到用户配置的止损提醒价"));
    }
    private Decision one(Type type,boolean match,String code,BigDecimal observed,BigDecimal threshold,String reason) {
        return new Decision(type,match,List.of(rule(match,code,observed,threshold,reason)));
    }
    private RuleResult rule(boolean match,String code,BigDecimal observed,BigDecimal threshold,String reason) {
        return new RuleResult(match,code,value(observed),value(threshold),reason+"（实际："+value(observed)+"；阈值："+value(threshold)+"）");
    }
    private String value(BigDecimal n) { return n==null ? "UNAVAILABLE" : n.stripTrailingZeros().toPlainString(); }
}
