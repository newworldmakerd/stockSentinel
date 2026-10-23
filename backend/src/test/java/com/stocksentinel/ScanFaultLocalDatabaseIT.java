package com.stocksentinel;

import com.stocksentinel.monitoring.*;
import com.stocksentinel.notification.*;
import com.stocksentinel.report.DailyReportService;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"scan-alerts.enabled=true","scan-alerts.worker-enabled=false","wecom.enabled=true",
    "wecom.bot-id=test-bot","wecom.bot-secret=test-only-credential","wecom.chat-id=test-group","llm.enabled=false",
    "market.enabled=false","intraday.enabled=false","intraday.schedule-enabled=false","jobs.schedule-enabled=false",
    "personal-price.enabled=false","reports.enabled=false","pullback.enabled=false","wecom.commands.enabled=false"})
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class ScanFaultLocalDatabaseIT {
    static final String SCHEMA="stocksentinel_fault_"+UUID.randomUUID().toString().replace("-","");
    static boolean created;
    static synchronized void prepare() {
        if(created)return;
        try(var c=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));var s=c.createStatement()) {
            s.execute("CREATE SCHEMA \""+SCHEMA+"\"");created=true;
        }catch(Exception e){throw new IllegalStateException("Could not create isolated fault test schema",e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        prepare();String base=System.getenv("STOCKSENTINEL_TEST_DB_URL");
        if(base.contains("currentSchema="))throw new IllegalArgumentException("Base test URL required");
        r.add("spring.datasource.url",()->base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
        r.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));
        r.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
        r.add("spring.flyway.default-schema",()->SCHEMA);r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.create-schemas",()->false);
    }
    @AfterAll static void cleanup() throws Exception {
        if(!created)return;if(!SCHEMA.matches("stocksentinel_fault_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe schema");
        try(var c=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));var s=c.createStatement()) {s.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ScanFaultService service;
    @Autowired ScanFaultDelivery delivery;
    @Autowired ScanFaultMapper mapper;
    @Autowired DailyReportService destination;
    @MockitoBean SmartBotClient bot;
    static final OffsetDateTime BASE=LocalDate.now(ZoneId.of("Asia/Shanghai")).atTime(10,0).atOffset(ZoneOffset.ofHours(8));
    @BeforeEach void setup() {
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
        jdbc.execute("TRUNCATE scan_fault_event,scan_fault_state,job_execution_item,job_execution,stock_info RESTART IDENTITY CASCADE");reset(bot);
        jdbc.update("INSERT INTO stock_info(symbol,name,exchange) VALUES('600487','亨通光电','SH'),('000731','四川美丰','SZ')");
        when(bot.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"ACK"));
    }
    long scan(int minute,String status,String itemStatus,String code) {return scanAt(BASE.plusMinutes(minute),status,itemStatus,code);}
    long scanAt(OffsetDateTime at,String status,String itemStatus,String code) {
        long id=jdbc.queryForObject("INSERT INTO job_execution(job_name,status,trade_date,trigger_type,started_at,finished_at,processed_count) VALUES('INTRADAY_SCAN',?,?, 'SCHEDULED',?, ?,1) RETURNING id",Long.class,status,at.toLocalDate(),at,at.plusSeconds(5));
        if(code!=null)jdbc.update("INSERT INTO job_execution_item(job_id,symbol,stage,status,code) VALUES(?,'600487','INTRADAY',?,?)",id,itemStatus,code);
        return id;
    }
    long fail(int minute) {var id=scan(minute,"PARTIAL","FAILED","MINUTE_PRIMARY_CONNECTION_BACKUP_HTTP_503");service.observe(id);return id;}
    void incident(){fail(0);fail(1);fail(2);}
    int events(String type){return jdbc.queryForObject("SELECT count(*) FROM scan_fault_event WHERE event_type=?",Integer.class,type);}
    int status(String status){return jdbc.queryForObject("SELECT count(*) FROM scan_fault_event WHERE status=?",Integer.class,status);}
    @Test void threeRoundsAggregateStocksAndDoNotCallModelOrGenerateStockSignal() {
        fail(0);fail(1);assertEquals(0,events("FAULT"));
        long id=scan(2,"PARTIAL","FAILED","MINUTE_HTTP_TIMEOUT");
        jdbc.update("INSERT INTO job_execution_item(job_id,symbol,stage,status,code) VALUES(?,'000731','INTRADAY','FAILED','MINUTE_HTTP_CONNECTION_FAILED')",id);
        service.observe(id);assertEquals(1,events("FAULT"));verifyNoInteractions(bot);
        var payload=jdbc.queryForObject("SELECT payload FROM scan_fault_event",String.class);
        assertTrue(payload.contains("亨通光电 600487"));assertTrue(payload.contains("四川美丰 000731"));assertTrue(payload.contains("连续异常：3轮"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM signal_event",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM signal_advice",Integer.class));
    }
    @Test void tRsiFaultUsesOwnStreakAndUnrelatedScanCannotClaimRecovery(){
        for(int i=0;i<3;i++)service.observe(scan(i,"FAILED","FAILED","T_RSI_P_TIMEOUT_B_CONNECTION"));
        assertEquals(1,events("FAULT"));assertEquals("T_RSI",jdbc.queryForObject("SELECT failure_kind FROM scan_fault_event",String.class));
        assertTrue(delivery.dispatchOne());service.observe(scan(3,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));assertEquals(0,events("RECOVERY"));
        long restored=scan(4,"SUCCESS","SUCCESS","NO_NEW_SIGNAL");
        jdbc.update("INSERT INTO scan_step(job_id,symbol,stage,status,code,started_at,finished_at,elapsed_ms) VALUES(?,'600487','T_RSI_OBSERVATION','SUCCESS','EVALUATED_NO_NEW_EVENT',?,?,1)",restored,BASE.plusMinutes(4),BASE.plusMinutes(4));
        service.observe(restored);assertEquals(1,events("RECOVERY"));
    }
    @Test void persistedCooldownAllowsOnlyOneRepeatAtFifteenMinutes() {
        for(int i=0;i<17;i++)fail(i);assertEquals(1,events("FAULT"));
        var id=fail(17);assertEquals(2,events("FAULT"));service.observe(id);assertEquals(2,events("FAULT"));
        assertEquals(18,jdbc.queryForObject("SELECT streak FROM scan_fault_state WHERE failure_kind='MINUTE_FETCH'",Integer.class));
    }
    @Test void recoveryIsSentOnceAfterFullSuccessfulScan() {
        incident();assertTrue(delivery.dispatchOne());assertEquals(1,status("SUCCESS"));
        service.observe(scan(3,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));assertEquals(1,events("RECOVERY"));
        assertTrue(delivery.dispatchOne());assertFalse(delivery.dispatchOne());
        service.observe(scan(4,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));assertEquals(1,events("RECOVERY"));
        verify(bot).send(eq("test-group"),contains("扫描异常提醒"));verify(bot).send(eq("test-group"),contains("扫描异常恢复"));
    }
    @Test void normalSkippedJobsNeitherAlertNorFalselyRecover() {
        for(int i=0;i<3;i++)service.observe(scan(i,"SKIPPED",null,null));assertEquals(0,events("FAULT"));
        incident();assertTrue(delivery.dispatchOne());
        service.observe(scan(3,"SKIPPED","SKIPPED","NO_CURRENT_TRADES"));
        service.observe(scanAt(BASE.withHour(12),"SKIPPED",null,null));assertEquals(0,events("RECOVERY"));
        fail(4);assertEquals(1,events("FAULT"));
    }
    @Test void partialOtherFailureCannotClaimRecovery() {
        incident();assertTrue(delivery.dispatchOne());
        service.observe(scan(3,"PARTIAL","FAILED","PERSONAL_PRICE_DATABASE_FAILED"));
        assertEquals(0,events("RECOVERY"));
        service.observe(scan(4,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));assertEquals(1,events("RECOVERY"));
    }
    @Test void staleMinuteDataAlertsButNoTradesDoesNot() {
        for(int i=0;i<3;i++)service.observe(scan(i,"SKIPPED","SKIPPED","NO_CURRENT_TRADES"));assertEquals(0,events("FAULT"));
        for(int i=3;i<6;i++)service.observe(scan(i,"SKIPPED","SKIPPED","STALE_MINUTES"));
        assertEquals(1,events("FAULT"));assertEquals("MINUTE_DATA",jdbc.queryForObject("SELECT failure_kind FROM scan_fault_event",String.class));
    }
    @Test void lunchBoundaryResetsUnannouncedStreak() {
        service.observe(scanAt(BASE.withHour(11).withMinute(28),"FAILED","FAILED","MINUTE_HTTP_502"));
        service.observe(scanAt(BASE.withHour(11).withMinute(29),"FAILED","FAILED","MINUTE_HTTP_502"));
        service.observe(scanAt(BASE.withHour(13).withMinute(1),"FAILED","FAILED","MINUTE_HTTP_502"));assertEquals(0,events("FAULT"));
        service.observe(scanAt(BASE.withHour(13).withMinute(2),"FAILED","FAILED","MINUTE_HTTP_502"));
        service.observe(scanAt(BASE.withHour(13).withMinute(3),"FAILED","FAILED","MINUTE_HTTP_502"));assertEquals(1,events("FAULT"));
    }
    @Test void unsentFaultIsCancelledAndRecoveryDoesNotCreateNoise() {
        incident();service.observe(scan(3,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));
        assertEquals(1,status("CANCELLED"));assertFalse(delivery.dispatchOne());verifyNoInteractions(bot);
    }
    @Test void concurrentObservationAndClaimDoNotDuplicate() throws Exception {
        fail(0);fail(1);long id=scan(2,"FAILED","FAILED","MINUTE_HTTP_502");
        try(var threads=Executors.newFixedThreadPool(2)) {
            var a=threads.submit(()->service.observe(id));var b=threads.submit(()->service.observe(id));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,events("FAULT"));assertNull(mapper.claim("different-destination",UUID.randomUUID().toString()));
        var row=mapper.claim(destination.destinationHash(),UUID.randomUUID().toString());assertNotNull(row);
        assertNull(mapper.claim(destination.destinationHash(),UUID.randomUUID().toString()));
        assertEquals(0,mapper.finish(row.id,"wrong-token","SUCCESS",null));
    }
    @Test void unknownSendIsNotRetriedAndRecoveryRemainsPossible() {
        incident();when(bot.send(anyString(),anyString())).thenThrow(new RuntimeException("private error"));
        assertTrue(delivery.dispatchOne());assertEquals(1,status("UNKNOWN"));assertFalse(delivery.dispatchOne());
        doReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"ACK")).when(bot).send(anyString(),anyString());
        service.observe(scan(3,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));assertTrue(delivery.dispatchOne());assertFalse(delivery.dispatchOne());
        verify(bot,times(2)).send(anyString(),anyString());
    }
    @Test void knownRetryIsBoundedAndStaleLeaseBecomesUnknown() {
        incident();when(bot.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.RETRY,"TEMPORARY"));
        for(int i=0;i<3;i++){assertTrue(delivery.dispatchOne());jdbc.update("UPDATE scan_fault_event SET next_retry_at=now()-interval '1 second'");}
        assertEquals(1,status("FAILED"));assertFalse(delivery.dispatchOne());verify(bot,times(3)).send(anyString(),anyString());
        jdbc.update("UPDATE scan_fault_event SET status='SENDING',lease_until=now()-interval '1 second'");mapper.expire();assertEquals(1,status("UNKNOWN"));
    }
    @Test void expiredPendingAlertIsNotSent() {
        incident();jdbc.update("UPDATE scan_fault_event SET created_at=now()-interval '16 minutes'");
        assertFalse(delivery.dispatchOne());assertEquals(1,status("EXPIRED"));verifyNoInteractions(bot);
    }
    @Test void briefFailureIsResetWithoutAnAlarmOrRecovery() {
        fail(0);fail(1);service.observe(scan(2,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));
        assertEquals(0,events("FAULT"));assertEquals(0,events("RECOVERY"));
        fail(3);fail(4);assertEquals(0,events("FAULT"));fail(5);assertEquals(1,events("FAULT"));
    }
    @Test void missingCalendarAlertsButExplicitHolidayDoesNot() {
        for(int i=0;i<3;i++) {
            var id=scan(i,"SKIPPED",null,null);jdbc.update("UPDATE job_execution SET error_message='MARKET_CLOSED' WHERE id=?",id);service.observe(id);
        }
        assertEquals(0,events("FAULT"));
        for(int i=3;i<6;i++) {
            var id=scan(i,"SKIPPED",null,null);jdbc.update("UPDATE job_execution SET error_message='CALENDAR_MISSING' WHERE id=?",id);service.observe(id);
        }
        assertEquals(1,events("FAULT"));assertTrue(jdbc.queryForObject("SELECT payload FROM scan_fault_event",String.class).contains("CALENDAR_MISSING"));
    }
    @Test void faultAndRecoveryUseActualLoopbackWebSocketProtocol() throws Exception {
        try(var server=new SmartBotProtocolTest.Bot("SUCCESS")) {
            var wire=server.client();
            try {
                when(bot.send(anyString(),anyString())).thenAnswer(call->wire.send(call.getArgument(0),call.getArgument(1)));
                incident();assertTrue(delivery.dispatchOne());
                service.observe(scan(3,"SUCCESS","SUCCESS","NO_NEW_SIGNAL"));assertTrue(delivery.dispatchOne());
                assertEquals(2,server.sends.get());assertEquals(List.of("test-group","test-group"),server.targets);
                assertEquals(2,status("SUCCESS"));assertNull(server.failure);
            } finally {wire.destroy();}
        }
    }
}
