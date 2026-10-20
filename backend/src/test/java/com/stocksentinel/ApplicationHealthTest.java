package com.stocksentinel;

import com.stocksentinel.advisory.AdviceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
    "llm.enabled=false"
})
class ApplicationHealthTest {
    @MockitoBean com.stocksentinel.report.ReportMapper reportMapper;
    @MockitoBean com.stocksentinel.commands.CommandMapper commandMapper;
    @MockitoBean AdviceStore store;
    @MockitoBean com.stocksentinel.market.MarketMapper marketMapper;
    @MockitoBean com.stocksentinel.indicator.IndicatorMapper indicatorMapper;
    @MockitoBean com.stocksentinel.stock.WatchlistMapper watchlistMapper;
    @MockitoBean com.stocksentinel.strategy.StrategyMapper strategyMapper;
    @MockitoBean com.stocksentinel.signal.SignalMapper signalMapper;
    @MockitoBean com.stocksentinel.notification.NotificationMapper notificationMapper;
    @MockitoBean com.stocksentinel.jobs.JobMapper jobMapper;
    @MockitoBean com.stocksentinel.intraday.IntradayMapper intradayMapper;
    @Autowired TestRestTemplate http;
    @Test void liveHttpHealthAndDisabledAdvisory() {
        assertEquals(200, http.getForEntity("/api/v1/health", String.class).getStatusCode().value());
        assertEquals(200, http.getForEntity("/actuator/health", String.class).getStatusCode().value());
        var monitoring=http.getForObject("/api/v1/monitoring/status",java.util.Map.class);
        assertEquals(false,monitoring.get("groupCommandsEnabled"));
        assertEquals("DISABLED",monitoring.get("wecomConnection"));
        assertEquals(503, http.postForEntity("/api/v1/signals/1/advice", null, String.class).getStatusCode().value());
        assertEquals(200,http.postForEntity("/api/v1/notifications/test",null,String.class).getStatusCode().value());
        assertEquals(503,http.postForEntity("/api/v1/notifications/test",
            java.util.Map.of("signalId",1,"dryRun",false),String.class).getStatusCode().value());
        assertEquals(503,http.postForEntity("/api/v1/jobs/daily-scan",
            java.util.Map.of("tradeDate","2026-10-19"),String.class).getStatusCode().value());
        assertEquals(503,http.postForEntity("/api/v1/intraday/scan",null,String.class).getStatusCode().value());
    }
}
