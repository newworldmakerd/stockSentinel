package com.stocksentinel.personal;

import java.math.BigDecimal;
import java.time.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class TObservationRules {
    private TObservationRules() {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Rsi(String symbol,String source,String adjustment,int period,OffsetDateTime observedAt,
        OffsetDateTime fiveAt,OffsetDateTime fifteenAt,BigDecimal rsi5,BigDecimal rsi15) {}
    public static boolean entryTime(LocalTime t){return (!t.isBefore(LocalTime.of(10,0))&&t.isBefore(LocalTime.of(11,31)))||(!t.isBefore(LocalTime.of(13,0))&&t.isBefore(LocalTime.of(14,50)));}
    public static String action(Rsi r){
        if(r.rsi15().compareTo(new BigDecimal("50"))>0&&r.rsi5().compareTo(new BigDecimal("80"))>0)return "SELL";
        if(r.rsi15().compareTo(new BigDecimal("50"))<0&&r.rsi5().compareTo(new BigDecimal("20"))<0)return "BUY";
        return "WAIT";
    }
    public static boolean fresh(String symbol,Rsi r,OffsetDateTime now){
        if(r==null||!symbol.equals(r.symbol())||!java.util.Set.of("AKSHARE_EASTMONEY_5M","EASTMONEY_5M","SINA_QFQ_5M").contains(r.source()==null?"":r.source())||!"QFQ".equals(r.adjustment())||r.period()!=14
            ||r.observedAt()==null||r.observedAt().isAfter(now.plusSeconds(5))||r.observedAt().isBefore(now.minusMinutes(6))
            ||!bar(r.fiveAt(),now,5)||!bar(r.fifteenAt(),now,15)||r.fifteenAt().isAfter(r.fiveAt())||!range(r.rsi5())||!range(r.rsi15()))return false;
        return true;
    }
    private static boolean range(BigDecimal r){return r!=null&&r.compareTo(BigDecimal.ZERO)>=0&&r.compareTo(new BigDecimal("100"))<=0;}
    private static boolean bar(OffsetDateTime at,OffsetDateTime now,int minutes){
        if(at==null||!at.isBefore(now)||at.isBefore(now.minusMinutes(minutes+1))||at.getSecond()!=0||at.getNano()!=0)return false;
        var local=at.atZoneSameInstant(TradingDatePolicy.ZONE);var t=local.toLocalTime();
        return local.toLocalDate().equals(now.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate())&&t.getMinute()%minutes==0
            &&((t.isAfter(LocalTime.of(9,30))&&!t.isAfter(LocalTime.of(11,30)))||(t.isAfter(LocalTime.of(13,0))&&!t.isAfter(LocalTime.of(15,0))));
    }
}
