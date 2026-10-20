package com.stocksentinel.commands;

import com.stocksentinel.notification.SmartBotClient;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Operational state only: no credentials, destination IDs or callback content. */
@RestController
public class MonitoringStatusController {
    private final ObjectProvider<SmartBotClient> bot;
    private final boolean enabled,schedule,commands;
    public MonitoringStatusController(ObjectProvider<SmartBotClient> bot,
            @Value("${intraday.enabled:false}") boolean enabled,
            @Value("${intraday.schedule-enabled:false}") boolean schedule,
            @Value("${wecom.commands.enabled:false}") boolean commands) {
        this.bot=bot;this.enabled=enabled;this.schedule=schedule;this.commands=commands;
    }
    @GetMapping("/api/v1/monitoring/status") public Object status() {
        var client=bot.getIfAvailable();
        return Map.of("intradayEnabled",enabled,"scheduleEnabled",schedule,"groupCommandsEnabled",commands,
            "wecomConnection",client==null ? "DISABLED" : client.discoveryStatus());
    }
}
