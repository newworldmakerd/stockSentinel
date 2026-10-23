package com.stocksentinel.intraday;

import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.signal.*;
import com.stocksentinel.strategy.engine.RuleResult;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.report.ReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

/** Independent minute rule, using a fixed previous-open-day MA60 in unadjusted price units. */
@Service
public class Ma60RiskService {
    public static final String STRATEGY="INTRADAY_MA60_V1";
    private final Ma60RiskMapper mapper;private final IntradayMapper minutes;private final SignalMapper signals;
    private final ReportMapper reports;private final TradingDatePolicy dates;private final Ma60RiskSettings settings;
    private final ObjectMapper json;private final Clock clock;private final ApplicationEventPublisher events;
    public Ma60RiskService(Ma60RiskMapper mapper,IntradayMapper minutes,SignalMapper signals,ReportMapper reports,
            TradingDatePolicy dates,Ma60RiskSettings settings,ObjectMapper json,Clock clock,ApplicationEventPublisher events) {
        this.mapper=mapper;this.minutes=minutes;this.signals=signals;this.reports=reports;this.dates=dates;
        this.settings=settings;this.json=json;this.clock=clock;this.events=events;
    }
    @Transactional public List<Long> evaluate(Config expected,Snapshot snapshot) {
        if(!settings.enabled())return List.of();
        var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));var today=now.toLocalDate();
        if(!"OPEN".equals(dates.calendarStatus(today))||!IntradayValidation.scanTime(now.toLocalTime()))return List.of();
        IntradayValidation.snapshot(expected.symbol,snapshot,now);
        var config=minutes.lock(expected.symbol);var watched=minutes.lockWatch(expected.symbol);
        if(config==null||!Boolean.TRUE.equals(config.enabled)||!Objects.equals(config.revision,expected.revision)||!Boolean.TRUE.equals(watched))return List.of();
        mapper.lockSymbol(expected.symbol);var state=mapper.lock(expected.symbol);
        if(state==null){state=new Ma60RiskMapper.State();state.symbol=expected.symbol;}
        if(!today.equals(state.tradeDate)){state.tradeDate=today;state.baselineKey=null;state.lastBarAt=null;state.lastTriggerAt=null;}
        var reference=reports.previousOpen(today);
        if(reference==null)return skip(state,"MA60_CALENDAR_REFERENCE_MISSING");
        var basis=mapper.basis(expected.symbol,reference);
        String invalid=validate(basis);
        if(invalid!=null)return skip(state,invalid);
        var quote=mapper.quote(expected.symbol,today);
        if(quote==null||quote.quotedAt==null||!today.equals(quote.quotedAt.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate())
                ||quote.quotedAt.isAfter(now.plusSeconds(5))||!positive(quote.previousClose))return skip(state,"MA60_CURRENT_REFERENCE_MISSING");
        if(quote.previousClose.compareTo(basis.referenceClose)!=0)return skip(state,"MA60_CORPORATE_ACTION_OR_REFERENCE_CHANGED");
        var threshold=basis.ma60.multiply(basis.referenceClose).divide(basis.qfqClose,16,RoundingMode.HALF_UP);
        if(threshold.signum()<=0||threshold.precision()-threshold.scale()>14)return skip(state,"MA60_INVALID_THRESHOLD");
        var baseline=new LinkedHashMap<String,Object>();baseline.put("referenceDate",reference);baseline.put("source",basis.source);
        baseline.put("ma60Qfq",basis.ma60);baseline.put("qfqClose",basis.qfqClose);baseline.put("unadjustedClose",basis.referenceClose);
        baseline.put("thresholdUnadjusted",threshold);baseline.put("adjustment","NONE");baseline.put("calculationVersion","ma-macd-vol-v1");
        baseline.put("currentDayPreviousClose",quote.previousClose);
        String key=key(reference+"|"+basis.source+"|"+canonical(basis.ma60)+"|"+canonical(basis.qfqClose)+"|"+canonical(basis.referenceClose));
        var at=snapshot.bars().getLast().barAt();
        if(!key.equals(state.baselineKey)) {
            state.referenceDate=reference;state.baselineKey=key;state.threshold=threshold;state.baseline=serialize(baseline);
            state.lastBarAt=at;state.status="READY";state.code="MA60_BASELINE_INITIALIZED";mapper.save(state);return List.of();
        }
        if(state.lastBarAt!=null&&!at.isAfter(state.lastBarAt))return List.of();
        boolean cross=crossed(snapshot,threshold);
        boolean cooled=state.lastTriggerAt==null||!at.isBefore(state.lastTriggerAt.plusMinutes(settings.cooldownMinutes()));
        state.lastBarAt=at;state.status="READY";state.code=cross&&!cooled?"MA60_CROSS_IN_COOLDOWN":"MA60_NO_NEW_CROSS";
        var ids=new ArrayList<Long>();
        if(cross&&cooled) {
            var bars=snapshot.bars();var previous=bars.get(bars.size()-2);var current=bars.getLast();
            var rules=List.of(new RuleResult(true,"INTRADAY_CROSS_BELOW_DAILY_MA60",
                "previous="+previous.closePrice()+";current="+current.closePrice(),threshold.toPlainString(),"连续完整分钟收盘价从上一开市日MA60上方或相等位置跌至下方"));
            var event=new SignalEvent();event.symbol=expected.symbol;event.tradeDate=today;event.signalAt=at;
            event.strategyCode=STRATEGY;event.strategyVersion="1.0.0";event.signalType="RISK_ALERT";event.referencePrice=current.closePrice();
            event.reason="盘中跌破60日均线：上一完整分钟 "+previous.closePrice()+" 元，当前完整分钟 "+current.closePrice()
                +" 元；MA60换算价 "+threshold.stripTrailingZeros().toPlainString()+" 元（日线依据 "+reference+"）。";
            event.riskNote="分钟行情可能延迟；MA60是60日均线而非60分钟均线，采用上一开市日固定基准；与收盘风险预警分别判断，不自动下单。";
            event.details=serialize(Map.of("timeframe","1m","baseline",baseline,"snapshot",snapshot,"rules",rules,"cooldownMinutes",settings.cooldownMinutes()));
            for(var bar:bars)minutes.bar(expected.symbol,bar,snapshot.source(),snapshot.observedAt());
            if(minutes.signal(event)==1){signals.rules(event.id,rules);events.publishEvent(new SignalCreated(event.id));ids.add(event.id);}
            state.lastTriggerAt=at;state.code="MA60_CROSS_TRIGGERED";
        }
        mapper.save(state);return ids;
    }
    private List<Long> skip(Ma60RiskMapper.State state,String code) {
        state.status="SKIPPED";state.code=code;state.baselineKey=null;state.baseline=null;state.threshold=null;state.referenceDate=null;state.lastBarAt=null;
        mapper.save(state);return List.of();
    }
    static String validate(Ma60RiskMapper.Basis b) {
        if(b==null||!positive(b.ma60)||!positive(b.qfqClose)||!positive(b.referenceClose)||b.historyCount==null||b.historyCount<120)return "MA60_DAILY_BASIS_MISSING";
        if(b.source==null||b.source.isBlank()||!b.source.equals(b.referenceSource)||!Long.valueOf(1).equals(b.sourceCount))return "MA60_MIXED_DAILY_SOURCE";
        if(b.calculatedAt==null||b.lastFetched==null||b.calculatedAt.isBefore(b.lastFetched))return "MA60_STALE_DAILY_INDICATOR";
        return null;
    }
    static boolean crossed(Snapshot snapshot,BigDecimal threshold) {
        var bars=snapshot.bars();if(bars.size()<2)return false;
        var before=bars.get(bars.size()-2);var current=bars.getLast();
        boolean sameSession=(before.barAt().atZoneSameInstant(TradingDatePolicy.ZONE).getHour()<12)==(current.barAt().atZoneSameInstant(TradingDatePolicy.ZONE).getHour()<12);
        return sameSession&&Duration.between(before.barAt(),current.barAt()).toSeconds()==60
            &&before.closePrice().compareTo(threshold)>=0&&current.closePrice().compareTo(threshold)<0;
    }
    private static boolean positive(BigDecimal value){return value!=null&&value.signum()>0;}
    private static String canonical(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static String key(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception failed){throw new IllegalStateException("MA60 key unavailable");}}
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception failed){throw new IllegalStateException("MA60 snapshot serialization failed");}}
}
