package com.stocksentinel.commands;

import com.stocksentinel.notification.SmartBotClient;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Operational state only: no credentials, destination IDs or callback content. */
@RestController
public class MonitoringStatusController {
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.notification.NotificationSettings destinations;
    private final ObjectProvider<SmartBotClient> bot;
    private final boolean enabled,schedule,commands;
    private final boolean chat;
    @Value("${jobs.enabled:false}") private boolean dailyJobs;
    @Value("${jobs.schedule-enabled:false}") private boolean dailySchedule;
    @Value("${personal-price.enabled:false}") private boolean personalPrice;
    @Value("${scan-alerts.enabled:false}") private boolean scanAlerts;
    @Value("${scan-alerts.worker-enabled:true}") private boolean scanAlertsWorker;
    @Value("${t-observation.enabled:false}") private boolean tObservation;
    public MonitoringStatusController(ObjectProvider<SmartBotClient> bot,
            @Value("${intraday.enabled:false}") boolean enabled,
            @Value("${intraday.schedule-enabled:false}") boolean schedule,
            @Value("${wecom.commands.enabled:false}") boolean commands,@Value("${wecom.commands.chat-enabled:false}") boolean chat) {
        this.bot=bot;this.enabled=enabled;this.schedule=schedule;this.commands=commands;this.chat=chat;
    }
    @GetMapping("/api/v1/monitoring/status") public Object status() {
        var client=bot.getIfAvailable();
        var status=new java.util.LinkedHashMap<String,Object>(Map.of("intradayEnabled",enabled,"scheduleEnabled",schedule,"groupCommandsEnabled",commands,"chatAssistantEnabled",chat,"dailyJobsEnabled",dailyJobs,"dailyScheduleEnabled",dailySchedule,"personalPriceEnabled",personalPrice,
            "wecomConnection",client==null ? "DISABLED" : client.discoveryStatus(),"scanAlertsEnabled",scanAlerts,"scanAlertsWorkerEnabled",scanAlertsWorker));
        status.put("configuredGroupCount",destinations==null?0:destinations.chatIds.size());
        status.put("wecomDiagnostics",client==null?Map.of("state","DISABLED"):client.connectionDiagnostics());
        status.put("watchlistScope","SHARED");status.put("tObservationEnabled",tObservation);return status;
    }
}
