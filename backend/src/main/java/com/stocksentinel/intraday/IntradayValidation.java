package com.stocksentinel.intraday;

import java.time.*;
import java.math.*;
import java.util.List;
import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.jobs.TradingDatePolicy;

public final class IntradayValidation {
    private IntradayValidation() {}
    public static class Skip extends RuntimeException {
        public final String code;public Skip(String code) {super(code);this.code=code;}
    }
    public static boolean scanTime(LocalTime t) {
        return (!t.isBefore(LocalTime.of(9,31)) && t.isBefore(LocalTime.of(11,32)))
            || (!t.isBefore(LocalTime.of(13,1)) && t.isBefore(LocalTime.of(15,2)));
    }
    private static int session(OffsetDateTime time) {
        var t=time.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalTime();
        return !t.isBefore(LocalTime.of(9,30)) && !t.isAfter(LocalTime.of(11,30)) ? 0 :
            !t.isBefore(LocalTime.of(13,0)) && !t.isAfter(LocalTime.of(15,0)) ? 1 : -1;
    }
    private static boolean window(List<Bar> bars,int size) {
        if(bars.size()<size) return false;
        var values=bars.subList(bars.size()-size,bars.size());int segment=session(values.getFirst().barAt());
        for(int i=0;i<values.size();i++) if(session(values.get(i).barAt())!=segment
                || (i>0 && Duration.between(values.get(i-1).barAt(),values.get(i).barAt()).toSeconds()!=60)) return false;
        return true;
    }
    public static void snapshot(String symbol,Snapshot data,OffsetDateTime now) {
        if(data==null || !symbol.equals(data.symbol()) || !"NONE".equals(data.adjustment()) || !"SHARE".equals(data.unit())
                || !"CNY".equals(data.amountUnit()) || data.source()==null || data.source().isBlank() || data.source().length()>40
                || data.observedAt()==null || data.observedAt().isAfter(now.plusSeconds(5)) || data.observedAt().isBefore(now.minusSeconds(30))
                || data.bars()==null || data.bars().size()>21 || data.metrics()==null) throw new Skip("INVALID_MINUTE_RESPONSE");
        if(data.bars().isEmpty()) throw new Skip("NO_MINUTE_DATA");
        OffsetDateTime previous=null;
        for(var b:data.bars()) {
            if(b==null || b.barAt()==null || !b.barAt().atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate().equals(now.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate())
                    || b.barAt().getSecond()!=0 || b.barAt().getNano()!=0 || session(b.barAt())<0
                    || !b.barAt().isBefore(now.withSecond(0).withNano(0)) || (previous!=null && !b.barAt().isAfter(previous))
                    || !price(b.openPrice()) || !price(b.highPrice()) || !price(b.lowPrice()) || !price(b.closePrice())
                    || b.lowPrice().compareTo(b.openPrice().min(b.closePrice()))>0 || b.highPrice().compareTo(b.openPrice().max(b.closePrice()))<0
                    || !quantity(b.volume()) || !(quantity(b.amount()) || (b.amount()==null && "TENCENT_1M".equals(data.source())))) throw new Skip("INVALID_MINUTE_BARS");
            previous=b.barAt();
        }
        if(data.bars().getLast().barAt().isBefore(now.minusSeconds(120))) throw new Skip("STALE_MINUTES");
        if(data.bars().getLast().volume().signum()==0) throw new Skip("NO_CURRENT_TRADES");
        var m=data.metrics();
        if(window(data.bars(),6)) {
            var base=data.bars().get(data.bars().size()-6).closePrice();
            var expected=data.bars().getLast().closePrice().subtract(base).multiply(BigDecimal.valueOf(100)).divide(base,16,RoundingMode.HALF_UP);
            if(m.fiveMinuteReturnPct()==null || m.fiveMinuteReturnPct().subtract(expected).abs().compareTo(new BigDecimal("0.000000000001"))>0) throw new Skip("INVALID_MINUTE_METRICS");
        } else if(m.fiveMinuteReturnPct()!=null) throw new Skip("CROSS_SESSION_METRICS");
        if(window(data.bars(),21)) {
            var preceding=data.bars().subList(0,20);
            var high=preceding.stream().map(Bar::highPrice).max(BigDecimal::compareTo).orElseThrow();
            var volume=preceding.stream().map(Bar::volume).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(m.previousTwentyHigh()==null || m.previousTwentyVolumeMean()==null || high.compareTo(m.previousTwentyHigh())!=0
                    || volume.compareTo(m.previousTwentyVolumeMean().multiply(BigDecimal.valueOf(20)))!=0) throw new Skip("INVALID_MINUTE_METRICS");
        } else if(m.previousTwentyHigh()!=null || m.previousTwentyVolumeMean()!=null) throw new Skip("CROSS_SESSION_METRICS");
    }
    public static boolean price(BigDecimal v) {return v!=null && v.signum()>0 && v.scale()<=4 && v.precision()-v.scale()<=14;}
    private static boolean quantity(BigDecimal v) {return v!=null && v.signum()>=0 && v.scale()<=4 && v.precision()-v.scale()<=20;}
}
