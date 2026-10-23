package com.stocksentinel.intraday;

import com.stocksentinel.jobs.*;
import java.time.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntradayScanService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(IntradayScanService.class);
    @Value("${stocksentinel.lock-namespace:76301}") private int lockNamespace;
    @Value("${intraday.scan-budget-seconds:55}") private int budgetSeconds=55;
    private java.util.function.LongSupplier scanTimer=System::nanoTime;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.pullback.PullbackService pullback;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.personal.PersonalPriceService personal;
    @org.springframework.beans.factory.annotation.Autowired private Ma60RiskService ma60Risk;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.personal.TObservationService tObservation;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.monitoring.ScanTelemetry telemetry;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<com.stocksentinel.monitoring.ScanFaultService> faults;
    private final IntradayMapper mapper;private final JobMapper jobs;private final IntradayClient client;
    private final IntradayWriter writer;private final TradingDatePolicy dates;private final Clock clock;
    private final ObjectProvider<DataSource> sources;private final boolean enabled,marketEnabled;
    public IntradayScanService(IntradayMapper mapper,JobMapper jobs,IntradayClient client,IntradayWriter writer,
            TradingDatePolicy dates,Clock clock,ObjectProvider<DataSource> sources,
            @Value("${intraday.enabled:false}") boolean enabled,@Value("${market.enabled:false}") boolean marketEnabled) {
        this.mapper=mapper;this.jobs=jobs;this.client=client;this.writer=writer;this.dates=dates;this.clock=clock;this.sources=sources;
        this.enabled=enabled;this.marketEnabled=marketEnabled;
    }
    public record Result(long jobId,String status,int processed,int signals,int failed,int skipped,String code) {}
    public Result scan(String trigger) {
        if(!enabled || !marketEnabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"INTRADAY_OR_MARKET_DISABLED");
        if(!java.util.Set.of("MANUAL","SCHEDULED").contains(trigger)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_TRIGGER");
        var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
        if(budgetSeconds<5||budgetSeconds>300)throw new IllegalStateException("Invalid intraday scan budget");
        long scanBegan=scanTimer.getAsLong();
        var job=new JobMapper.Execution();job.date=now.toLocalDate();job.trigger=trigger;mapper.start(job);
        int processed=0,success=0,count=0,failed=0,skipped=0;
        try(var connection=sources.getObject().getConnection()) {
            boolean acquired;
            try(var statement=connection.createStatement();var rs=statement.executeQuery("SELECT pg_try_advisory_lock("+lockNamespace+",2)")) {rs.next();acquired=rs.getBoolean(1);}
            if(!acquired) return end(job.id,"SKIPPED",0,0,0,0,0,"INTRADAY_ALREADY_RUNNING");
            try {
                mapper.recover(job.id);
                if(!IntradayValidation.scanTime(now.toLocalTime())) return end(job.id,"SKIPPED",0,0,0,0,0,"OUTSIDE_SESSION");
                String calendar=dates.calendarStatus(now.toLocalDate());
                if(!"OPEN".equals(calendar)) return end(job.id,"SKIPPED",0,0,0,0,0,calendar);
                var monitors=mapper.monitors();
                var configs=new java.util.LinkedHashMap<String,com.stocksentinel.intraday.IntradayContracts.Config>();
                monitors.forEach(c->configs.put(c.symbol,c));
                var pool=new java.util.LinkedHashMap<String,com.stocksentinel.pullback.PullbackMapper.Candidate>();
                pullback.pool().forEach(c->pool.put(c.symbol,c));
                var symbols=new java.util.LinkedHashSet<>(configs.keySet());symbols.addAll(pool.keySet());
                var personalSymbols=new java.util.HashSet<>(personal.symbols());symbols.addAll(personalSymbols);
                var tSymbols=new java.util.HashSet<String>();
                if(tObservation!=null&&com.stocksentinel.personal.TObservationRules.entryTime(now.toLocalTime()))tSymbols.addAll(tObservation.symbols());
                symbols.addAll(tSymbols);
                if(symbols.isEmpty()) return end(job.id,"SKIPPED",0,0,0,0,0,"EMPTY_INTRADAY_WATCHLIST");
                for(var symbol:symbols) {
                    processed++;
                    if(remaining(scanBegan)<1){skipped++;jobs.item(job.id,symbol,"INTRADAY","SKIPPED","SCAN_BUDGET_EXHAUSTED");continue;}
                    var currentTime=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
                    if(!currentTime.toLocalDate().equals(job.date) || !IntradayValidation.scanTime(currentTime.toLocalTime())) {
                        skipped++;jobs.item(job.id,symbol,"INTRADAY","SKIPPED","SESSION_ENDED");continue;
                    }
                    String stage="MINUTE_FETCH";
                    try {
                        var data=step(job.id,symbol,"MINUTE_FETCH",null,()->client.minutes(symbol,remaining(scanBegan)));
                        stage="PERSONAL_PRICE";
                        if(personalSymbols.contains(symbol))count+=step(job.id,symbol,"PERSONAL_PRICE",data,()->personal.evaluate(symbol,data));
                        var ids=new java.util.ArrayList<Long>();
                        stage="INTRADAY_WRITE";
                        if(configs.containsKey(symbol)){var generated=step(job.id,symbol,"INTRADAY_WRITE",data,()->writer.write(configs.get(symbol),data));ids.addAll(generated);link(job.id,generated);}
                        stage="PULLBACK_CONFIRM";
                        if(pool.containsKey(symbol)){var generated=step(job.id,symbol,"PULLBACK_CONFIRM",data,()->pullback.confirm(pool.get(symbol),data));ids.addAll(generated);link(job.id,generated);}
                        count+=ids.size();
                        stage="MA60_RISK";
                        if(ma60Risk!=null&&configs.containsKey(symbol)) {
                            var riskIds=step(job.id,symbol,"MA60_RISK",data,()->ma60Risk.evaluate(configs.get(symbol),data));count+=riskIds.size();ids.addAll(riskIds);link(job.id,riskIds);
                        }
                        stage="T_RSI_OBSERVATION";
                        if(tSymbols.contains(symbol)){
                            if(remaining(scanBegan)<1)throw new IntradayValidation.Skip("T_RSI_SCAN_BUDGET_EXHAUSTED");
                            var rsi=step(job.id,symbol,"T_RSI_FETCH",null,()->client.tRsi(symbol,remaining(scanBegan)));
                            count+=step(job.id,symbol,"T_RSI_OBSERVATION",rsi,()->tObservation.evaluate(symbol,data,rsi,job.id));
                        }
                        success++;
                        jobs.item(job.id,symbol,"INTRADAY","SUCCESS",ids.isEmpty() ? "NO_NEW_SIGNAL" : "GENERATED_"+ids.size());
                    } catch(IntradayValidation.Skip skip) {
                        skipped++;jobs.item(job.id,symbol,"INTRADAY","SKIPPED",skip.code);
                    } catch(Exception failure) {
                        String code=failureCode(stage,failure);
                        log.warn("intraday_failure job={} symbol={} stage={} code={} type={}",job.id,symbol,stage,code,failure.getClass().getSimpleName());
                        failed++;jobs.item(job.id,symbol,"INTRADAY","FAILED",code);
                    }
                }
                String status=failed>0 ? (success>0 ? "PARTIAL" : "FAILED") : skipped>0 ? (success>0 ? "PARTIAL" : "SKIPPED") : "SUCCESS";
                return end(job.id,status,processed,success,count,failed,skipped,"INTRADAY_COMPLETED");
            } finally {
                try(var statement=connection.createStatement()) {statement.execute("SELECT pg_advisory_unlock("+lockNamespace+",2)");}
                catch(Exception releaseFailed) {connection.abort(Runnable::run);}
            }
        } catch(Exception failure) {
            log.warn("intraday_job_failure job={} type={}",job.id,failure.getClass().getSimpleName());
            return end(job.id,"FAILED",processed,success,count,failed,skipped,"INTRADAY_JOB_FAILED");
        }
    }
    private static String failureCode(String stage,Exception failure) {
        if(failure instanceof MinuteFetchException fetch)return fetch.code;
        if(failure instanceof org.springframework.dao.DataAccessException)return stage+"_DATABASE_FAILED";
        return stage+"_FAILED";
    }
    private int remaining(long began){return (int)Math.max(0,budgetSeconds-(scanTimer.getAsLong()-began)/1_000_000_000L);}
    private <T> T step(long job,String symbol,String stage,Object data,java.util.function.Supplier<T> action){
        var started=OffsetDateTime.now(clock);long begin=System.nanoTime();String status="SUCCESS",code="COMPLETED";Object observed=data;
        try{T value=action.get();if(data==null)observed=value;
            if(value instanceof java.util.Collection<?> rows)code=rows.isEmpty()?"EVALUATED_NO_NEW_EVENT":"EVENTS_"+rows.size();
            if(value instanceof Integer n)code=n==0?"EVALUATED_NO_NEW_EVENT":"EVENTS_"+n;
            return value;
        }catch(IntradayValidation.Skip skipped){status="SKIPPED";code=skipped.code;throw skipped;}
        catch(RuntimeException failure){status="FAILED";code=failureCode(stage,failure);throw failure;}
        finally{if(telemetry!=null)telemetry.record(job,symbol,stage,status,code,started,OffsetDateTime.now(clock),(System.nanoTime()-begin)/1_000_000,observed);}
    }
    private void link(long job,java.util.List<Long> ids){if(telemetry!=null)ids.forEach(id->telemetry.link(job,id));}
    private Result end(long id,String status,int processed,int success,int count,int failed,int skipped,String code) {
        jobs.finish(id,status,processed,success,failed,skipped,code);
        // Alert queue failures must not turn a committed stock scan into a failed scan.
        try {if(faults!=null){var observer=faults.getIfAvailable();if(observer!=null)observer.observe(id);}}
        catch(Exception alertFailure){log.warn("scan_fault_observer_failed job={} type={}",id,alertFailure.getClass().getSimpleName());}
        return new Result(id,status,processed,count,failed,skipped,code);
    }
}
