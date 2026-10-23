package com.stocksentinel.custom;
import com.fasterxml.jackson.databind.*;import com.stocksentinel.strategy.StrategyMapper;import com.stocksentinel.indicator.IndicatorMapper;
import com.stocksentinel.market.MarketContracts.Adjustment;import com.stocksentinel.common.entity.*;
import java.time.*;import java.util.*;import java.math.BigDecimal;
import static com.stocksentinel.custom.CustomRuleEngine.*;
import com.stocksentinel.jobs.TradingDatePolicy;

public class CustomFactorReader {
    private final StrategyMapper bars;private final IndicatorMapper indicators;private final CustomStrategyMapper custom;private final ObjectMapper json;private final TradingDatePolicy dates;
    public CustomFactorReader(StrategyMapper bars,IndicatorMapper indicators,CustomStrategyMapper custom,ObjectMapper json,TradingDatePolicy dates){this.bars=bars;this.indicators=indicators;this.custom=custom;this.json=json;this.dates=dates;}
    public record Snapshot(String code,String source,Map<Factor,Value> values){}
    public Snapshot read(String symbol,LocalDate day,Adjustment adjustment){
        var state=bars.state(symbol,adjustment,day);if(state.total<1||state.sourceCount!=1||state.invalidRows>0||state.lastFetched==null)return new Snapshot("MISSING_OR_INVALID_HISTORY",null,Map.of());
        var data=bars.recent(symbol,adjustment,day);if(data.isEmpty()||!day.equals(data.getFirst().tradeDate))return new Snapshot("MISSING_CURRENT_BAR",null,Map.of());
        if(data.getFirst().source==null||data.getFirst().source.isBlank()||data.stream().limit(2).anyMatch(b->b.amount!=null&&b.amount.signum()<0))return new Snapshot("INVALID_HISTORY",null,Map.of());
        var current=bar(data.getFirst());var previous=data.size()<2?new EnumMap<Factor,BigDecimal>(Factor.class):bar(data.get(1));
        var calculated=indicators.latest(symbol,adjustment,"ma-macd-vol-v1",day);
        for(var row:calculated){if(row.calculatedAt==null||row.calculatedAt.isBefore(state.lastFetched))continue;if(day.equals(row.tradeDate))current.putAll(indicators(row));else if(data.size()>1&&data.get(1).tradeDate.equals(row.tradeDate))previous.putAll(indicators(row));}
        current.putAll(chips(symbol,day));if(data.size()>1)previous.putAll(chips(symbol,data.get(1).tradeDate));
        var values=new EnumMap<Factor,Value>(Factor.class);for(var f:Factor.values())values.put(f,new Value(current.get(f),previous.get(f)));
        return new Snapshot("AVAILABLE",data.getFirst().source,values);
    }
    private EnumMap<Factor,BigDecimal> bar(MarketDailyBar b){var out=new EnumMap<Factor,BigDecimal>(Factor.class);out.put(Factor.CLOSE,b.closePrice);out.put(Factor.OPEN,b.openPrice);out.put(Factor.HIGH,b.highPrice);out.put(Factor.LOW,b.lowPrice);out.put(Factor.VOLUME,b.volume);out.put(Factor.AMOUNT,b.amount);return out;}
    private EnumMap<Factor,BigDecimal> indicators(MarketIndicatorDaily i){var out=new EnumMap<Factor,BigDecimal>(Factor.class);out.put(Factor.MA5,positive(i.ma5));out.put(Factor.MA10,positive(i.ma10));out.put(Factor.MA20,positive(i.ma20));out.put(Factor.MA60,positive(i.ma60));out.put(Factor.DIF,i.dif);out.put(Factor.DEA,i.dea);out.put(Factor.MACD_HIST,i.macdHist);out.put(Factor.VOLUME_RATIO,i.volumeRatio!=null&&i.volumeRatio.signum()>=0?i.volumeRatio:null);return out;}
    private BigDecimal positive(BigDecimal value){return value!=null&&value.signum()>0?value:null;}
    private Map<Factor,BigDecimal> chips(String symbol,LocalDate day){if(day.equals(dates.today())&&!dates.confirmedSource(day,"CHIP_EASTMONEY"))return Map.of();String raw=custom.chips(symbol,day);if(raw==null)return Map.of();try{var m=json.readTree(raw);if(!day.toString().equals(m.path("as_of").asText())||!symbol.equals(m.path("symbol").asText())||!"CHIP_EASTMONEY".equals(m.path("source").asText())||!"akshare-cyq-210-v1".equals(m.path("version").asText()))return Map.of();return Map.of(Factor.CHIP90,ratio(m,"concentration90"),Factor.CHIP70,ratio(m,"concentration70"),Factor.CHIP_PROFIT,ratio(m,"profit_ratio"));}catch(Exception e){return Map.of();}}
    private BigDecimal ratio(JsonNode m,String key){var n=new BigDecimal(m.path(key).asText());if(n.signum()<0||n.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException();return n;}
}
