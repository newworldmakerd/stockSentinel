package com.stocksentinel.intraday;

import java.time.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class IntradayScheduler {
    private final IntradayScanService scans;private final Clock clock;private final boolean enabled;
    public IntradayScheduler(IntradayScanService scans,Clock clock,@Value("${intraday.schedule-enabled:false}") boolean enabled) {
        this.scans=scans;this.clock=clock;this.enabled=enabled;
    }
    @Scheduled(cron="5 * * * * *",zone="Asia/Shanghai",scheduler="intradayTaskScheduler")
    public void tick() {
        if(enabled && IntradayValidation.scanTime(LocalTime.now(clock.withZone(TradingDatePolicy.ZONE)))) scans.scan("SCHEDULED");
    }
}
