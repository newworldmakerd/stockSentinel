package com.stocksentinel.jobs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyScanScheduler {
    private final DailyScanService jobs;
    private final TradingDatePolicy dates;
    private final boolean enabled;
    public DailyScanScheduler(DailyScanService jobs,TradingDatePolicy dates,@Value("${jobs.schedule-enabled:false}") boolean enabled) {
        this.jobs=jobs;this.dates=dates;this.enabled=enabled;
    }
    @Scheduled(cron="0 15 15 * * *",zone="Asia/Shanghai")
    public void closeScan() { if(enabled) jobs.run(dates.today(),"SCHEDULED"); }
}
