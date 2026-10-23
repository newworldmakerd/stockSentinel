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
    @MockitoBean com.stocksentinel.monitoring.ScanCoverageMapper scanCoverageMapper;
    @MockitoBean com.stocksentinel.monitoring.ScanRecoveryMapper scanRecoveryMapper;
    @MockitoBean com.stocksentinel.trend.TrendMapper trendMapper;
    @MockitoBean com.stocksentinel.personal.TTradeLedgerMapper tTradeLedgerMapper;
    @MockitoBean com.stocksentinel.monitoring.ScanTelemetryMapper scanTelemetryMapper;
    @MockitoBean com.stocksentinel.personal.TObservationMapper tObservationMapper;
    @MockitoBean com.stocksentinel.personal.PositionMapper positionMapper;
    @Test void operationalDiagnosticsHaveHttpContractsAndDropPrivateFields(){
        org.mockito.Mockito.when(scanTelemetryMapper.exists(77L)).thenReturn(true);
        org.mockito.Mockito.when(scanTelemetryMapper.steps(77L)).thenReturn(java.util.List.of(java.util.Map.of("job_id",77L,"stage","MINUTE_FETCH","status","FAILED","code","MINUTE_HTTP_TIMEOUT","payload","private-not-returned")));
        var steps=http.getForEntity("/api/v1/monitoring/jobs/77/steps",com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(200,steps.getStatusCode().value());assertFalse(steps.getBody().get(0).has("payload"));assertEquals("SOURCE_FAILURE",steps.getBody().get(0).path("category").asText());
        org.mockito.Mockito.when(scanTelemetryMapper.trace(88L)).thenReturn(java.util.Map.of("id",88L,"status","UNKNOWN","user_id","private-user","target_chat_id","private-group","payload","private-message"));
        org.mockito.Mockito.when(scanTelemetryMapper.jobs(88L)).thenReturn(java.util.List.of(77L));
        var trace=http.getForEntity("/api/v1/monitoring/notifications/88/trace",com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(200,trace.getStatusCode().value());assertEquals("DELIVERY_UNKNOWN",trace.getBody().path("category").asText());assertFalse(trace.getBody().has("payload"));assertFalse(trace.getBody().has("user_id"));assertFalse(trace.getBody().has("target_chat_id"));
        assertEquals(404,http.getForEntity("/api/v1/monitoring/jobs/999999/steps",String.class).getStatusCode().value());
    }
    @MockitoBean com.stocksentinel.report.SelectionExplanationMapper selectionExplanationMapper;
    @MockitoBean com.stocksentinel.intraday.Ma60RiskMapper ma60RiskMapper;
    @MockitoBean com.stocksentinel.monitoring.ScanFaultMapper scanFaultMapper;
    @MockitoBean com.stocksentinel.personal.PersonalPriceMapper personalPriceMapper;
    @MockitoBean com.stocksentinel.commands.GroupAssistantMapper groupAssistantMapper;
    @MockitoBean com.stocksentinel.pullback.PullbackDigestMapper pullbackDigestMapper;
    @MockitoBean com.stocksentinel.pullback.PullbackMapper pullbackMapper;
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
    @Test void coverageHttpDefaultsToPreviewAndRejectsUnboundedRequests(){
        var day=java.time.LocalDate.of(2026,10,20);var open=new com.stocksentinel.common.entity.MarketCalendar();open.exchange="CN";open.isOpen=true;
        org.mockito.Mockito.when(jobMapper.calendar(day)).thenReturn(open);
        var request=java.util.Map.of("pipeline","TREND_HISTORY","date",day.toString(),"symbols",java.util.List.of("000731"));
        var preview=http.postForEntity("/api/v1/monitoring/coverage/retries",request,com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(200,preview.getStatusCode().value());assertTrue(preview.getBody().path("dryRun").asBoolean());
        org.mockito.Mockito.verify(scanRecoveryMapper,org.mockito.Mockito.never()).requestInsert(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertEquals(400,http.getForEntity("/api/v1/monitoring/coverage/failures?pipeline=TREND_QUOTES&limit=101",String.class).getStatusCode().value());
        var invalid=http.postForEntity("/api/v1/monitoring/coverage/retries",java.util.Map.of("pipeline","TREND_HISTORY","date",day.toString(),"symbols",java.util.List.of("bad")),com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(400,invalid.getStatusCode().value());assertEquals("INVALID_COVERAGE_REQUEST",invalid.getBody().path("error").asText());
        assertEquals(503,http.postForEntity("/api/v1/monitoring/coverage/retries",java.util.Map.of("pipeline","TREND_HISTORY","date",day.toString(),"symbols",java.util.List.of("000731"),"requestId","contract","dryRun",false),String.class).getStatusCode().value());
        org.mockito.Mockito.when(scanRecoveryMapper.request("missing")).thenReturn(null);
        assertEquals(404,http.getForEntity("/api/v1/monitoring/coverage/retries/missing",String.class).getStatusCode().value());
        assertEquals(200,http.getForEntity("/api/v1/monitoring/coverage",String.class).getStatusCode().value());
    }
    @Test void liveHttpHealthAndDisabledAdvisory() {
        assertFalse(http.getForObject("/api/v1/chips",com.fasterxml.jackson.databind.JsonNode.class).path("enabled").asBoolean());
        assertEquals(200,http.postForEntity("/api/v1/chips/scan",null,String.class).getStatusCode().value());
        assertEquals(503,http.postForEntity("/api/v1/chips/scan",java.util.Map.of("dryRun",false),String.class).getStatusCode().value());
        var trend=http.getForObject("/api/v1/trend",com.fasterxml.jackson.databind.JsonNode.class);assertFalse(trend.path("enabled").asBoolean());assertEquals(200,http.postForEntity("/api/v1/trend/scan",null,String.class).getStatusCode().value());assertEquals(503,http.postForEntity("/api/v1/trend/scan",java.util.Map.of("dryRun",false),String.class).getStatusCode().value());
        var document=http.getForObject("/api/v1/documents/wecom",com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals("DISABLED",document.path("status").asText());assertFalse(document.has("mcpUrl"));
        var setup=http.postForObject("/api/v1/documents/wecom/setup",null,com.fasterxml.jackson.databind.JsonNode.class);
        assertTrue(setup.path("dryRun").asBoolean());
        assertEquals(503,http.postForEntity("/api/v1/documents/wecom/setup",java.util.Map.of("dryRun",false),String.class).getStatusCode().value());
        assertEquals(200, http.getForEntity("/api/v1/health", String.class).getStatusCode().value());
        assertEquals(200, http.getForEntity("/actuator/health", String.class).getStatusCode().value());
        var monitoring=http.getForObject("/api/v1/monitoring/status",java.util.Map.class);
        assertEquals(false,monitoring.get("groupCommandsEnabled"));
        assertEquals(false,monitoring.get("scanAlertsEnabled"));
        assertEquals("DISABLED",monitoring.get("wecomConnection"));
        var pullback=http.getForObject("/api/v1/pullback",java.util.Map.class);
        var digest=(java.util.Map<?,?>)pullback.get("recommendations");
        assertEquals(false,digest.get("enabled"));assertEquals(2,digest.get("frequencyPerTradingDay"));assertEquals(java.util.List.of("09:40","15:30"),digest.get("times"));assertEquals(5,digest.get("maxCandidates"));
        assertEquals(503, http.postForEntity("/api/v1/signals/1/advice", null, String.class).getStatusCode().value());
        assertEquals(200,http.postForEntity("/api/v1/notifications/test",null,String.class).getStatusCode().value());
        assertEquals(503,http.postForEntity("/api/v1/notifications/test",
            java.util.Map.of("signalId",1,"dryRun",false),String.class).getStatusCode().value());
        assertEquals(503,http.postForEntity("/api/v1/jobs/daily-scan",
            java.util.Map.of("tradeDate","2026-10-19"),String.class).getStatusCode().value());
        assertEquals(503,http.postForEntity("/api/v1/intraday/scan",null,String.class).getStatusCode().value());
    }
}
