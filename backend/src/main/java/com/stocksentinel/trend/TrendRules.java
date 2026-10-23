package com.stocksentinel.trend;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
public final class TrendRules {
    private TrendRules(){}
    public static BigDecimal n(JsonNode x,String name){try{var v=new BigDecimal(x.path(name).asText());if(v.precision()>40)throw new IllegalArgumentException();return v;}catch(Exception e){throw new IllegalArgumentException("TREND_NUMBER_INVALID");}}
    public static boolean market(JsonNode index){return index!=null&&n(index,"close").compareTo(n(index,"ma200"))>0;}
    public static boolean trend(JsonNode m){return n(m,"close").compareTo(n(m,"ma60"))>0&&n(m,"ma60").compareTo(n(m,"ma120"))>0&&n(m,"ma60_change20").signum()>0;}
    public static boolean buy(JsonNode m,BigDecimal price,BigDecimal amount,boolean preview,TrendSettings settings){return trend(m)&&price.compareTo(n(m,preview?"recent_twenty_high":"previous_twenty_high").multiply(n(m,"raw_factor")))>0&&amount!=null&&amount.compareTo(settings.minAmount)>=0;}
    public static BigDecimal initial(BigDecimal price,BigDecimal atr,TrendSettings s){if(price.signum()<=0||atr.signum()<=0)throw new IllegalArgumentException("TREND_ATR_INVALID");return price.subtract(atr.multiply(s.initialAtr)).setScale(8,RoundingMode.HALF_UP);}
    public static BigDecimal trail(BigDecimal prior,BigDecimal highest,BigDecimal atr,TrendSettings s){return prior.max(highest.subtract(atr.multiply(s.trailingAtr))).setScale(8,RoundingMode.HALF_UP);}
    public static long shares(BigDecimal equity,BigDecimal totalValue,BigDecimal stockValue,BigDecimal entry,BigDecimal stop,String symbol,TrendSettings s){
        if(equity.signum()<=0||totalValue.signum()<0||stockValue.signum()<0||stockValue.compareTo(totalValue)>0||totalValue.compareTo(equity)>0||entry.signum()<=0||stop.signum()<=0||entry.compareTo(stop)<=0)throw new IllegalArgumentException("TREND_RISK_INPUT_INVALID");
        var risk=equity.multiply(s.riskRate).divide(entry.subtract(stop),0,RoundingMode.DOWN);
        var money=equity.multiply(s.stockCap).subtract(stockValue).min(equity.multiply(s.portfolioCap).subtract(totalValue)).min(equity.subtract(totalValue)).max(BigDecimal.ZERO);
        long count=risk.min(money.divide(entry,0,RoundingMode.DOWN)).divide(BigDecimal.valueOf(100),0,RoundingMode.DOWN).longValueExact()*100;
        return symbol.startsWith("688")||symbol.startsWith("689")?count<200?0:count:count;
    }
}
