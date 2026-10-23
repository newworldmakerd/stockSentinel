package com.stocksentinel.pullback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.market.*;
import com.stocksentinel.strategy.engine.*;
import com.stocksentinel.intraday.*;
import com.stocksentinel.intraday.IntradayContracts.Snapshot;
import com.stocksentinel.signal.*;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.report.ReportMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class PullbackService {
    private final PullbackMapper mapper;private final PullbackSettings settings;private final MarketMapper market;
    private final ReportMapper reports;private final IntradayMapper minutes;private final SignalMapper signals;
    private final ObjectMapper json;private final ApplicationEventPublisher events;private final Clock clock;private final TradingDatePolicy dates;
    public PullbackService(PullbackMapper mapper,PullbackSettings settings,MarketMapper market,ReportMapper reports,
            IntradayMapper minutes,SignalMapper signals,ObjectMapper json,ApplicationEventPublisher events,Clock clock,TradingDatePolicy dates) {
        this.mapper=mapper;this.settings=settings;this.market=market;this.reports=reports;this.minutes=minutes;
        this.signals=signals;this.json=json;this.events=events;this.clock=clock;this.dates=dates;
    }
    public PullbackSettings settings(){return settings;}
    public List<PullbackMapper.Candidate> pool() {
        if(!settings.enabled())return List.of();
        var previous=reports.previousOpen(dates.today());
        return previous==null?List.of():mapper.active(previous,settings.version(),settings.poolLimit());
    }
    @Transactional
    public void observe(String symbol,LocalDate asOf,BigDecimal average,StrategyContext context) {
        if(!settings.enabled())return;
        var bars=market.daily(symbol,asOf.minusYears(1),asOf,MarketContracts.Adjustment.QFQ);
        var decision=PullbackRules.evaluate(bars,context,settings);
        if(!decision.matched())return;
        var reference=context.referenceBar();
        if(!asOf.equals(reference.tradeDate) || reference.highPrice==null || reference.highPrice.signum()<=0)return;
        var row=new PullbackMapper.Candidate();row.symbol=symbol;row.dataDate=asOf;row.version=settings.version();
        row.averageAmount=average;row.referenceHigh=reference.highPrice;
        try {row.details=json.writeValueAsString(Map.of("parameters",settings,"rules",decision.rules(),"context",context,"asOf",asOf));}
        catch(Exception invalid){throw new IllegalStateException("Could not serialize pullback observation");}
        mapper.insert(row);
    }
    @Transactional
    public List<Long> confirm(PullbackMapper.Candidate expected,Snapshot snapshot) {
        if(!settings.enabled() || !settings.version().equals(expected.version))return List.of();
        var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
        if(!"OPEN".equals(dates.calendarStatus(now.toLocalDate())) || !IntradayValidation.scanTime(now.toLocalTime())
                || !expected.dataDate.equals(reports.previousOpen(now.toLocalDate())))return List.of();
        IntradayValidation.snapshot(expected.symbol,snapshot,now);
        var row=mapper.lock(expected);if(row==null || row.triggeredAt!=null)return List.of();
        var bar=snapshot.bars().getLast();var at=bar.barAt();
        if(row.lastBarAt!=null && !at.isAfter(row.lastBarAt))return List.of();
        var mean=snapshot.metrics().previousTwentyVolumeMean();
        boolean breakout=bar.closePrice().compareTo(row.referenceHigh)>0;
        boolean volume=mean!=null && mean.signum()>0 && bar.volume().compareTo(mean.multiply(settings.minuteMultiplier()))>=0;
        if(!breakout || !volume){mapper.advance(row,at,false);return List.of();}
        for(var b:snapshot.bars())minutes.bar(row.symbol,b,snapshot.source(),snapshot.observedAt());
        var rules=List.of(new RuleResult(true,"PREVIOUS_DAY_HIGH",bar.closePrice().toPlainString(),row.referenceHigh.toPlainString(),"完整分钟收盘突破前一交易日不复权最高价"),
            new RuleResult(true,"MINUTE_VOLUME",bar.volume().toPlainString(),mean.multiply(settings.minuteMultiplier()).toPlainString(),"分钟成交量达到此前20个连续完整分钟均量的"+settings.minuteMultiplier()+"倍"));
        var event=new SignalEvent();event.symbol=row.symbol;event.tradeDate=now.toLocalDate();event.signalAt=at;
        event.strategyCode="STRONG_PULLBACK_V1";event.strategyVersion=row.version;event.signalType="PULLBACK_STRENGTH";event.referencePrice=bar.closePrice();
        event.reason="强势股缩量回踩已入观察池（日线 "+row.dataDate+"）；分钟收盘 "+bar.closePrice()+" 突破昨日高点 "+row.referenceHigh+"；分钟量能≥"+settings.minuteMultiplier()+"倍。";
        event.riskNote="初始参数尚未回测；盘中突破可能失败，跌回突破价下方或日线跌破MA20需重新评估；不自动下单。";
        try{event.details=json.writeValueAsString(Map.of("observation",json.readTree(row.details),"snapshot",snapshot,"rules",rules));}
        catch(Exception invalid){throw new IllegalStateException("Could not serialize pullback confirmation");}
        if(minutes.signal(event)!=1)return List.of();
        signals.rules(event.id,rules);mapper.advance(row,at,true);events.publishEvent(new SignalCreated(event.id));return List.of(event.id);
    }
}
