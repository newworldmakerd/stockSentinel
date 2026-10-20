package com.stocksentinel.report;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportScheduler {
    private final DailyReportService reports;private final ScreeningService screening;private final ReportMapper mapper;
    private final TradingDatePolicy dates;private final Clock clock;private final boolean enabled,worker;
    public ReportScheduler(DailyReportService reports,ScreeningService screening,ReportMapper mapper,TradingDatePolicy dates,Clock clock,
        @Value("${reports.schedule-enabled:false}") boolean enabled,@Value("${reports.worker-enabled:true}") boolean worker) {
        this.reports=reports;this.screening=screening;this.mapper=mapper;this.dates=dates;this.clock=clock;this.enabled=enabled;this.worker=worker;
    }
    @Scheduled(cron="0 * * * * *",zone="Asia/Shanghai",scheduler="reportTaskScheduler")
    public void tick() {
        if(!enabled || !"OPEN".equals(dates.calendarStatus(dates.today())))return;
        var time=LocalTime.now(clock.withZone(TradingDatePolicy.ZONE));if(time.isBefore(LocalTime.of(9,35)) || time.isAfter(LocalTime.of(23,0)))return;
        screening.start();
        if(!time.isBefore(LocalTime.of(15,30))) {
            var run=mapper.run(dates.today());if(run!=null && (run.overview!=null || !"RUNNING".equals(run.status)))reports.enqueue(dates.today());
        }
    }
    @Scheduled(cron="0 25 15 * * *",zone="Asia/Shanghai",scheduler="reportTaskScheduler")
    public void closeOverview(){if(enabled && "OPEN".equals(dates.calendarStatus(dates.today())))screening.refreshOverview();}
    @Scheduled(fixedDelayString="${reports.poll-ms:5000}",initialDelayString="${reports.poll-ms:5000}",scheduler="reportTaskScheduler")
    public void deliver(){if(worker)reports.deliverOne();}
}
