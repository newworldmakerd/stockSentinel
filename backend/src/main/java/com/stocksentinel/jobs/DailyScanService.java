package com.stocksentinel.jobs;

import com.stocksentinel.market.*;
import com.stocksentinel.market.MarketContracts.Adjustment;
import com.stocksentinel.indicator.IndicatorService;
import com.stocksentinel.strategy.*;
import java.time.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** One synchronous bounded scan; external calls never run in a database transaction. */
@Service
public class DailyScanService {
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.monitoring.ScanTelemetry telemetry;
    private final JobMapper jobs;
    private final TradingDatePolicy dates;
    private final MarketCollectionService market;
    private final MarketMapper bars;
    private final IndicatorService indicators;
    private final StrategyDefinitionService definitions;
    private final StrategyEvaluationService evaluation;
    private final ObjectProvider<DataSource> sources;
    private final boolean enabled;
    public DailyScanService(JobMapper jobs,TradingDatePolicy dates,MarketCollectionService market,MarketMapper bars,
            IndicatorService indicators,StrategyDefinitionService definitions,StrategyEvaluationService evaluation,
            ObjectProvider<DataSource> sources,@Value("${jobs.enabled:false}") boolean enabled) {
        this.jobs=jobs;this.dates=dates;this.market=market;this.bars=bars;this.indicators=indicators;
        this.definitions=definitions;this.evaluation=evaluation;this.sources=sources;this.enabled=enabled;
    }
    public record Result(long jobId,String status,int processed,int success,int failed,int skipped,String code) {}
    public Result run(LocalDate date,String trigger) {
        if(!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"JOBS_DISABLED");
        if(date==null || date.isAfter(dates.today()) || !java.util.Set.of("MANUAL","SCHEDULED").contains(trigger))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_JOB_DATE_OR_TRIGGER");
        var job=new JobMapper.Execution();job.date=date;job.trigger=trigger;jobs.start(job);
        int processed=0,success=0,failed=0,skipped=0;
        try(var connection=sources.getObject().getConnection()) {
            boolean locked;
            try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_try_advisory_lock(76301,1)")) {
                result.next();locked=result.getBoolean(1);
            }
            if(!locked) return end(job.id,"SKIPPED",0,0,0,0,"JOB_ALREADY_RUNNING");
            try {
                jobs.recover(job.id);
                String calendar=dates.calendarStatus(date);
                if(!"OPEN".equals(calendar)) return end(job.id,"SKIPPED",0,0,0,0,calendar);
                if(date.equals(dates.today()) && !dates.allowToday(date)) return end(job.id,"SKIPPED",0,0,0,0,"CLOSE_NOT_CONFIRMED");
                var watchlist=jobs.watchlist();
                if(watchlist.isEmpty()) return end(job.id,"SKIPPED",0,0,0,0,"EMPTY_WATCHLIST");
                var definition=definitions.current(StrategyDefinitionService.CODE);
                if(!Boolean.TRUE.equals(definition.enabled)) return end(job.id,"SKIPPED",0,0,0,0,"STRATEGY_DISABLED");
                var params=definitions.parameters(definition.parameters);
                for(var stock:watchlist) {
                    processed++;String stage="MARKET";
                    try {
                        LocalDate start=date.minusDays(730),first=jobs.firstDate(stock.symbol,params.adjustment().name());
                        if(first!=null && first.isBefore(start)) start=first;
                        Instant fetchStarted=Instant.now();
                        market.collect(stock.symbol,start,date,params.adjustment());
                        if(params.adjustment()!=Adjustment.NONE) market.collect(stock.symbol,start,date,Adjustment.NONE);
                        var current=bars.daily(stock.symbol,date,date,params.adjustment());
                        var reference=bars.daily(stock.symbol,date,date,Adjustment.NONE);
                        if(current.size()!=1 || reference.size()!=1
                                || current.getFirst().fetchedAt.toInstant().isBefore(fetchStarted)
                                || reference.getFirst().fetchedAt.toInstant().isBefore(fetchStarted)) {
                            skipped++;jobs.item(job.id,stock.symbol,stage,"SKIPPED","CURRENT_FINAL_BAR_MISSING");continue;
                        }
                        stage="INDICATORS";indicators.calculate(stock.symbol,params.adjustment(),date);
                        stage="STRATEGY";var result=evaluation.evaluateWatched(StrategyDefinitionService.CODE,stock.symbol,date);
                        if(telemetry!=null&&result.signals()!=null)result.signals().stream().filter(com.stocksentinel.signal.SignalWriter.Saved::created).forEach(s->telemetry.link(job.id,s.id()));
                        if("SKIPPED".equals(result.status())) {
                            skipped++;jobs.item(job.id,stock.symbol,stage,"SKIPPED",result.reason());
                        } else {
                            success++;jobs.item(job.id,stock.symbol,stage,"SUCCESS",result.status());
                        }
                    } catch(Exception failure) {
                        failed++;jobs.item(job.id,stock.symbol,stage,"FAILED",stage+"_FAILED");
                    }
                }
                String status=failed>0 ? (success>0 ? "PARTIAL" : "FAILED") : skipped>0 ? (success>0 ? "PARTIAL" : "SKIPPED") : "SUCCESS";
                return end(job.id,status,processed,success,failed,skipped,"SCAN_COMPLETED");
            } finally {
                try(var statement=connection.createStatement()) { statement.execute("SELECT pg_advisory_unlock(76301,1)"); }
                catch(Exception releaseFailed) { connection.abort(Runnable::run); }
            }
        } catch(Exception failure) { return end(job.id,"FAILED",processed,success,failed,skipped,"JOB_EXECUTION_FAILED"); }
    }
    private Result end(long id,String status,int processed,int success,int failed,int skipped,String code) {
        jobs.finish(id,status,processed,success,failed,skipped,code);
        return new Result(id,status,processed,success,failed,skipped,code);
    }
}
