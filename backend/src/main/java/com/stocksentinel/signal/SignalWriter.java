package com.stocksentinel.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.common.entity.StrategyDefinition;
import com.stocksentinel.indicator.IndicatorMapper;
import com.stocksentinel.strategy.*;
import com.stocksentinel.strategy.engine.SwingTrendStrategy.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SignalWriter {
    public record Saved(long id,String signalType,boolean created) {}
    private final SignalMapper mapper;
    private final StrategyMapper strategies;
    private final StrategyDefinitionService definitions;
    private final IndicatorMapper indicators;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final com.stocksentinel.jobs.TradingDatePolicy dates;
    public SignalWriter(SignalMapper mapper,StrategyMapper strategies,StrategyDefinitionService definitions,
            IndicatorMapper indicators,ObjectMapper json,ApplicationEventPublisher events) {
        this(mapper,strategies,definitions,indicators,json,events,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SignalWriter(SignalMapper mapper,StrategyMapper strategies,StrategyDefinitionService definitions,
            IndicatorMapper indicators,ObjectMapper json,ApplicationEventPublisher events,com.stocksentinel.jobs.TradingDatePolicy dates) {
        this.mapper=mapper; this.strategies=strategies; this.definitions=definitions;
        this.indicators=indicators; this.json=json; this.events=events;
        this.dates=dates;
    }
    @Transactional public List<Saved> save(StrategyDefinition definition,StrategyContextReader.Prepared prepared,List<Decision> decisions) {
        var context=prepared.context(); var current=context.currentBar();
        boolean manuallyConfirmed=dates!=null && current.tradeDate.equals(dates.today());
        if(manuallyConfirmed && (!dates.confirmedSource(current.tradeDate,current.source)
                || !dates.confirmedSource(current.tradeDate,context.referenceBar().source))) throw conflict("CLOSE_CONFIRMATION_REQUIRED");
        var active=strategies.shared(definition.code);
        if (active==null || !Boolean.TRUE.equals(active.enabled) || !active.version.equals(definition.version)
                || !definitions.same(active.parameters,definition.parameters)) throw conflict("STRATEGY_CHANGED_RETRY");
        var state=strategies.state(current.symbol,context.parameters().adjustment(),current.tradeDate);
        var values=indicators.latest(current.symbol,context.parameters().adjustment(),context.parameters().calculationVersion(),current.tradeDate);
        var reference=strategies.reference(current.symbol,current.tradeDate);
        if (state.total!=prepared.history().total || !Objects.equals(state.lastFetched,prepared.history().lastFetched)
                || values.size()!=2 || !Objects.equals(values.get(0).calculatedAt,context.current().calculatedAt)
                || !Objects.equals(values.get(1).calculatedAt,context.previous().calculatedAt)
                || reference==null || !Objects.equals(reference.fetchedAt,context.referenceBar().fetchedAt)
                || reference.closePrice.compareTo(context.referenceBar().closePrice)!=0) throw conflict("DATA_CHANGED_RETRY");
        var saved=new ArrayList<Saved>();
        for (var decision:decisions) {
            if (!decision.matched()) continue;
            var signal=new SignalEvent();
            signal.symbol=current.symbol; signal.tradeDate=current.tradeDate;
            signal.strategyCode=definition.code; signal.strategyVersion=definition.version; signal.signalType=decision.signalType().name();
            signal.referencePrice=context.referenceBar().closePrice;
            signal.reason=decision.rules().stream().map(rule->rule.explanation()).collect(Collectors.joining("；"));
            signal.riskNote="基于指定历史日线的规则观察，不自动下单；数据修订或条件不再满足时需重新评估。";
            try {
                var snapshot=json.createObjectNode();
                snapshot.set("strategyParameters",json.readTree(definition.parameters));
                snapshot.put("calculationVersion",context.parameters().calculationVersion());
                snapshot.put("indicatorAdjustment",context.parameters().adjustment().name());
                snapshot.put("referenceAdjustment","NONE");
                if(manuallyConfirmed) snapshot.set("closeConfirmation",json.readTree(dates.evidence(current.tradeDate,current.source)));
                snapshot.set("currentBar",json.valueToTree(context.currentBar()));
                snapshot.set("previousBar",json.valueToTree(context.previousBar()));
                snapshot.set("referenceBar",json.valueToTree(context.referenceBar()));
                snapshot.set("currentIndicator",json.valueToTree(context.current()));
                snapshot.set("previousIndicator",json.valueToTree(context.previous()));
                snapshot.put("previousFiveVolumeSum",context.previousFiveVolumeSum());
                snapshot.set("history",json.valueToTree(prepared.history()));
                snapshot.set("rules",json.valueToTree(decision.rules()));
                signal.details=json.writeValueAsString(snapshot);
            } catch (Exception failed) { throw new IllegalStateException("Could not serialize rule snapshot",failed); }
            if (mapper.insert(signal)==1) {
                mapper.rules(signal.id,decision.rules());
                events.publishEvent(new SignalCreated(signal.id));
                saved.add(new Saved(signal.id,signal.signalType,true));
            } else {
                var existing=mapper.existing(signal.symbol,signal.tradeDate,signal.strategyCode,signal.strategyVersion,signal.signalType);
                if (existing==null) throw new IllegalStateException("Signal conflict without existing record");
                saved.add(new Saved(existing.id,existing.signalType,false));
            }
        }
        return saved;
    }
    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT,code); }
}
