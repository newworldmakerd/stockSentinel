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
        var job=new JobMapper.Execution();job.date=now.toLocalDate();job.trigger=trigger;mapper.start(job);
        int processed=0,success=0,count=0,failed=0,skipped=0;
        try(var connection=sources.getObject().getConnection()) {
            boolean acquired;
            try(var statement=connection.createStatement();var rs=statement.executeQuery("SELECT pg_try_advisory_lock(76301,2)")) {rs.next();acquired=rs.getBoolean(1);}
            if(!acquired) return end(job.id,"SKIPPED",0,0,0,0,0,"INTRADAY_ALREADY_RUNNING");
            try {
                mapper.recover(job.id);
                if(!IntradayValidation.scanTime(now.toLocalTime())) return end(job.id,"SKIPPED",0,0,0,0,0,"OUTSIDE_SESSION");
                String calendar=dates.calendarStatus(now.toLocalDate());
                if(!"OPEN".equals(calendar)) return end(job.id,"SKIPPED",0,0,0,0,0,calendar);
                var monitors=mapper.monitors();
                if(monitors.isEmpty()) return end(job.id,"SKIPPED",0,0,0,0,0,"EMPTY_INTRADAY_WATCHLIST");
                for(var config:monitors) {
                    processed++;
                    var currentTime=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
                    if(!currentTime.toLocalDate().equals(job.date) || !IntradayValidation.scanTime(currentTime.toLocalTime())) {
                        skipped++;jobs.item(job.id,config.symbol,"INTRADAY","SKIPPED","SESSION_ENDED");continue;
                    }
                    try {
                        var data=client.minutes(config.symbol);
                        var ids=writer.write(config,data);count+=ids.size();success++;
                        jobs.item(job.id,config.symbol,"INTRADAY","SUCCESS",ids.isEmpty() ? "NO_NEW_SIGNAL" : "GENERATED_"+ids.size());
                    } catch(IntradayValidation.Skip skip) {
                        skipped++;jobs.item(job.id,config.symbol,"INTRADAY","SKIPPED",skip.code);
                    } catch(Exception failure) {
                        failed++;jobs.item(job.id,config.symbol,"INTRADAY","FAILED","INTRADAY_PROCESSING_FAILED");
                    }
                }
                String status=failed>0 ? (success>0 ? "PARTIAL" : "FAILED") : skipped>0 ? (success>0 ? "PARTIAL" : "SKIPPED") : "SUCCESS";
                return end(job.id,status,processed,success,count,failed,skipped,"INTRADAY_COMPLETED");
            } finally {
                try(var statement=connection.createStatement()) {statement.execute("SELECT pg_advisory_unlock(76301,2)");}
                catch(Exception releaseFailed) {connection.abort(Runnable::run);}
            }
        } catch(Exception failure) {return end(job.id,"FAILED",processed,success,count,failed,skipped,"INTRADAY_JOB_FAILED");}
    }
    private Result end(long id,String status,int processed,int success,int count,int failed,int skipped,String code) {
        jobs.finish(id,status,processed,success,failed,skipped,code);return new Result(id,status,processed,count,failed,skipped,code);
    }
}
