package com.stocksentinel.intraday;

import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.signal.*;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntradayWriter {
    private final IntradayMapper mapper;
    private final SignalMapper signals;
    private final WatchlistMapper watchlist;
    private final IntradayRules rules;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    public IntradayWriter(IntradayMapper mapper,SignalMapper signals,WatchlistMapper watchlist,IntradayRules rules,ObjectMapper json,ApplicationEventPublisher events,Clock clock) {
        this.mapper=mapper;this.signals=signals;this.watchlist=watchlist;this.rules=rules;this.json=json;this.events=events;this.clock=clock;
    }
    @Transactional public List<Long> write(Config expected,Snapshot snapshot) {
        var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
        IntradayValidation.snapshot(expected.symbol,snapshot,now);
        var config=mapper.lock(expected.symbol);
        var watched=mapper.lockWatch(expected.symbol);
        if(config==null || !config.enabled || !config.revision.equals(expected.revision) || !Boolean.TRUE.equals(watched))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"MONITOR_CHANGED_RETRY");
        var at=snapshot.bars().getLast().barAt();var cursor=mapper.cursor(config.symbol);
        if(cursor!=null && (cursor.lastBarAt.isAfter(at) || (cursor.lastBarAt.isEqual(at) && cursor.configRevision.equals(config.revision)))) return List.of();
        for(var bar:snapshot.bars()) mapper.bar(config.symbol,bar,snapshot.source(),snapshot.observedAt());
        var created=new ArrayList<Long>();
        for(var decision:rules.evaluate(config,snapshot)) {
            String type=decision.type().name();var state=mapper.state(config.symbol,type);
            var last=state==null ? null : state.lastTriggerAt;
            boolean price=decision.type()==Type.TAKE_PROFIT || decision.type()==Type.STOP_LOSS;
            boolean armed=state==null || !Boolean.TRUE.equals(state.active) || !config.revision.equals(state.configRevision)
                || (last!=null && !last.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate().equals(at.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate()));
            boolean cooled=last==null || !at.isBefore(last.plusMinutes(config.cooldownMinutes));
            if(decision.matched() && cooled && (!price || armed)) {
                var event=new SignalEvent();event.symbol=config.symbol;event.signalAt=at;
                event.tradeDate=at.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate();
                event.strategyCode="INTRADAY_V1";event.strategyVersion="1.0.0-r"+config.revision;
                event.signalType=type;event.referencePrice=snapshot.bars().getLast().closePrice();
                event.reason=decision.rules().stream().map(r->r.explanation()).collect(java.util.stream.Collectors.joining("；"));
                event.riskNote="盘中分钟行情存在延迟；突破仅为观察，价位提醒不代表持仓或交易指令；不自动下单。";
                try {
                    var details=json.createObjectNode();details.put("timeframe","1m");details.put("adjustment","NONE");
                    details.put("calculationVersion","intraday-metrics-v1");details.set("parameters",json.valueToTree(config.parameters()));
                    details.set("snapshot",json.valueToTree(snapshot));details.set("rules",json.valueToTree(decision.rules()));event.details=json.writeValueAsString(details);
                } catch(Exception invalid) {throw new IllegalStateException("Could not serialize minute signal");}
                if(mapper.signal(event)==1) {
                    signals.rules(event.id,decision.rules());events.publishEvent(new SignalCreated(event.id));created.add(event.id);last=at;
                }
            }
            mapper.updateState(config.symbol,type,decision.matched(),config.revision,last);
        }
        mapper.updateCursor(config.symbol,at,config.revision);return created;
    }
}
