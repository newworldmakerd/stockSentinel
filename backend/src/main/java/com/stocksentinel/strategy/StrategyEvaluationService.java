package com.stocksentinel.strategy;

import com.stocksentinel.signal.SignalWriter;
import com.stocksentinel.strategy.engine.SwingTrendStrategy;
import com.stocksentinel.indicator.IndicatorService;
import com.stocksentinel.stock.WatchlistService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StrategyEvaluationService {
    public record Result(String status,String reason,String strategyCode,String strategyVersion,
                         List<SwingTrendStrategy.Decision> evaluations,List<SignalWriter.Saved> signals) {}
    private final StrategyDefinitionService definitions;
    private final StrategyContextReader reader;
    private final SwingTrendStrategy engine;
    private final SignalWriter writer;
    private final com.stocksentinel.jobs.TradingDatePolicy dates;
    public StrategyEvaluationService(StrategyDefinitionService definitions,StrategyContextReader reader,SwingTrendStrategy engine,SignalWriter writer) {
        this(definitions,reader,engine,writer,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StrategyEvaluationService(StrategyDefinitionService definitions,StrategyContextReader reader,SwingTrendStrategy engine,SignalWriter writer,
            com.stocksentinel.jobs.TradingDatePolicy dates) {
        this.definitions=definitions; this.reader=reader; this.engine=engine; this.writer=writer;
        this.dates=dates;
    }
    public Result evaluate(String code,String symbol,LocalDate date) {
        WatchlistService.symbol(symbol);
        var definition=definitions.current(code);
        var parameters=definitions.parameters(definition.parameters);
        IndicatorService.validate(symbol,parameters.adjustment(),date,dates!=null && dates.allowToday(date));
        if (!Boolean.TRUE.equals(definition.enabled)) return new Result("SKIPPED","STRATEGY_DISABLED",code,definition.version,List.of(),List.of());
        StrategyContextReader.Prepared context;
        try { context=reader.read(symbol,date,parameters); }
        catch (StrategyContextReader.Skipped skipped) { return new Result("SKIPPED",skipped.code,code,definition.version,List.of(),List.of()); }
        if(dates!=null && date.equals(dates.today()) && (!dates.confirmedSource(date,context.context().currentBar().source)
                || !dates.confirmedSource(date,context.context().referenceBar().source)))
            return new Result("SKIPPED","CLOSE_SOURCE_UNCONFIRMED",code,definition.version,List.of(),List.of());
        var decisions=engine.evaluate(context.context());
        if (decisions.stream().noneMatch(SwingTrendStrategy.Decision::matched))
            return new Result("NO_SIGNAL","NO_RULE_GROUP_MATCHED",code,definition.version,decisions,List.of());
        var saved=writer.save(definition,context,decisions);
        return new Result(saved.stream().anyMatch(SignalWriter.Saved::created) ? "GENERATED" : "ALREADY_EXISTS",
            "RULES_EVALUATED",code,definition.version,decisions,saved);
    }
}
