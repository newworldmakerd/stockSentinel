package com.stocksentinel;

import com.stocksentinel.jobs.*;
import com.stocksentinel.market.*;
import com.stocksentinel.market.MarketContracts.*;
import com.stocksentinel.stock.*;
import com.stocksentinel.notification.*;
import com.stocksentinel.advisory.*;
import com.stocksentinel.strategy.*;
import java.time.*;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(DailyScanLocalDatabaseIT.ClockConfig.class)
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class DailyScanLocalDatabaseIT {
    static final LocalDate TODAY=LocalDate.now(TradingDatePolicy.ZONE),DAY=TODAY.minusDays(1);
    @TestConfiguration(proxyBeanMethods=false) static class ClockConfig {
        @Bean @Primary Clock testClock() { return Clock.fixed(TODAY.atTime(15,20).atZone(TradingDatePolicy.ZONE).toInstant(),TradingDatePolicy.ZONE); }
    }
    private static final String SCHEMA="stocksentinel_task06_"+UUID.randomUUID().toString().replace("-","");
    private static boolean created;
    private static synchronized void prepareSchema() {
        if (created) return;
        if (!SCHEMA.matches("stocksentinel_task06_[a-f0-9]{32}")) throw new IllegalStateException("Invalid isolated schema name");
        try (var connection=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),
                System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
             var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA \""+SCHEMA+"\"");
            created=true;
        } catch (Exception failed) { throw new IllegalStateException("Could not create isolated TASK-06 test schema",failed); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        prepareSchema();
        registry.add("spring.datasource.url",()->{
            String base=System.getenv("STOCKSENTINEL_TEST_DB_URL");
            if (base.contains("currentSchema=")) throw new IllegalArgumentException("Use base test URL without currentSchema");
            return base+(base.contains("?") ? "&" : "?")+"currentSchema="+SCHEMA;
        });
        registry.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
        registry.add("spring.flyway.default-schema",()->SCHEMA);
        registry.add("spring.flyway.schemas",()->SCHEMA);
        registry.add("spring.flyway.create-schemas",()->false);
        registry.add("market.enabled",()->true);
        registry.add("market.python-url",DailyScanLocalDatabaseIT::pythonUrl);
        registry.add("jobs.enabled",()->true);
        registry.add("jobs.schedule-enabled",()->false);
        registry.add("llm.model",()->"job-fixture-model");
        registry.add("llm.enabled",()->false);
        registry.add("wecom.enabled",()->true);
        registry.add("wecom.bot-id",()->"test-bot");
        registry.add("wecom.bot-secret",()->"test-only-credential");
        registry.add("wecom.chat-id",()->"test-group");
        registry.add("wecom.worker-enabled",()->false);
    }
    @AfterAll static void dropOwnSchema() throws Exception {
        if (!created) return;
        if (!SCHEMA.matches("stocksentinel_task06_[a-f0-9]{32}")) throw new IllegalStateException("Refusing unsafe schema cleanup");
        try (var connection=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),
                System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
             var statement=connection.createStatement()) {
            statement.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
            created=false;
        }
    }
    private static Process python;
    private static String url;
    private static synchronized String pythonUrl() {
        if (python!=null && python.isAlive()) return url;
        try {
            var root=Path.of("..").toAbsolutePath().normalize();
            Path executable=root.resolve(".venv/Scripts/python.exe");
            if (!java.nio.file.Files.exists(executable)) executable=root.resolve(".venv/bin/python");
            int port;
            try (var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
            url="http://127.0.0.1:"+port;
            var builder=new ProcessBuilder(executable.toString(),"quant-service/tests/job_fixture_server.py",String.valueOf(port));
            builder.directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("task06-python-integration.log").toFile());
            builder.environment().keySet().removeIf(key->key.startsWith("POSTGRES_") || key.startsWith("STOCKSENTINEL_TEST_")
                || key.startsWith("LLM_") || key.startsWith("WECOM_") || key.equals("DB_URL"));
            builder.environment().put("MARKET_PROVIDER_ENABLED","false");
            python=builder.start();
            var http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build();
            for (int attempt=0;attempt<100;attempt++) {
                if (!python.isAlive()) throw new IllegalStateException("Local Python exited; inspect integration log");
                try {
                    var request=HttpRequest.newBuilder(URI.create(url+"/health")).timeout(java.time.Duration.ofSeconds(1)).build();
                    if (http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200) return url;
                } catch (java.io.IOException ignored) { }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Local Python readiness timeout");
        } catch (Exception failure) { stopPython(); throw new IllegalStateException("Could not start local compute service",failure); }
    }
    @AfterAll static void stopPython() {
        if (python!=null && python.isAlive()) {
            var descendants=python.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            python.destroy();
            try {
                if (!python.waitFor(10,TimeUnit.SECONDS)) python.destroyForcibly();
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            } catch (InterruptedException interrupted) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                python.destroyForcibly(); Thread.currentThread().interrupt();
            }
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TradingDatePolicy dates;
    @Autowired DailyScanService scans;
    @Autowired JobMapper jobs;
    @Autowired MarketWriter market;
    @Autowired WatchlistService watchlist;
    @Autowired NotificationWorker worker;
    @Autowired DataSource dataSource;
    @Autowired MockMvc http;
    @MockitoBean SmartBotClient client;
    @MockitoBean AdvisoryClient advisory;
    @BeforeEach void setup() throws Exception {
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
        // Only the verified, owned random schema is cleared; public is never a target.
        jdbc.execute("TRUNCATE job_execution_item,notify_record,signal_advice,signal_rule_hit,signal_event,market_indicator_daily,market_daily_bar,stock_watchlist,stock_info,market_close_confirmation,market_calendar,job_execution RESTART IDENTITY CASCADE");
        reset(client,advisory);
        when(advisory.analyze(anyString())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new Advice("固定测试建议",List.of("固定测试依据"),List.of("固定测试风险"),"固定测试失效条件");
        });
    }
    void open(LocalDate date) { dates.importCalendar(List.of(new TradingDatePolicy.CalendarEntry(date,true,"TEST_EXCHANGE_CALENDAR"))); }
    void stock(String symbol,boolean enabled) {
        market.stocks(List.of(new Stock(symbol,"job fixture","SH")));
        watchlist.add(symbol,"test fixture");watchlist.update(symbol,enabled,"test fixture");
    }
    @Test void fullHttpMarketRealPythonIndicatorsSignalAdviceAndSocketNotification() throws Exception {
        open(DAY);stock("999610",true);
        var run=scans.run(DAY,"MANUAL");assertEquals("SUCCESS",run.status());assertEquals(1,run.success());
        assertEquals(242,jdbc.queryForObject("SELECT count(*) FROM market_daily_bar",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM market_indicator_daily",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM signal_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM signal_advice WHERE status='SUCCESS'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notify_record WHERE status='PENDING'",Integer.class));
        try(var bot=new SmartBotProtocolTest.Bot("SUCCESS")) {var wire=bot.client();try {
            when(client.send(anyString(),anyString())).thenAnswer(call->wire.send(call.getArgument(0),call.getArgument(1)));
            assertTrue(worker.dispatchOne());assertEquals(1,bot.sends.get());
        }finally {wire.destroy();}}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notify_record WHERE status='SUCCESS'",Integer.class));
        var second=scans.run(DAY,"MANUAL");assertEquals("SUCCESS",second.status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM signal_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notify_record",Integer.class));
        verify(advisory,times(1)).analyze(anyString());assertFalse(worker.dispatchOne());
        http.perform(get("/api/v1/jobs/"+run.jobId())).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].code").value("GENERATED"));
    }
    @Test void unknownCalendarAndClosedDayNeverCollect() {
        stock("999610",true);
        assertEquals("CALENDAR_MISSING",scans.run(DAY,"MANUAL").code());
        dates.importCalendar(List.of(new TradingDatePolicy.CalendarEntry(DAY,false,"TEST_EXCHANGE_CALENDAR")));
        assertEquals("MARKET_CLOSED",scans.run(DAY,"MANUAL").code());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM market_daily_bar",Integer.class));verifyNoInteractions(client,advisory);
    }
    @Test void emptyOrDisabledWatchlistNeverCollects() {
        open(DAY);stock("999610",false);assertEquals("EMPTY_WATCHLIST",scans.run(DAY,"MANUAL").code());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM market_daily_bar",Integer.class));
    }
    @Test void todayRemainsSkippedUntilExactSourceConfirmed() {
        open(TODAY);stock("999610",true);
        assertEquals("CLOSE_NOT_CONFIRMED",scans.run(TODAY,"MANUAL").code());
        dates.confirm(TODAY,"OTHER_SOURCE");
        var rejected=scans.run(TODAY,"MANUAL");assertEquals("SKIPPED",rejected.status());assertEquals(1,rejected.skipped());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM signal_event",Integer.class));
        dates.confirm(TODAY,"JOB_FIXTURE");var accepted=scans.run(TODAY,"MANUAL");
        assertEquals("SUCCESS",accepted.status());assertEquals(1,accepted.success());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE trade_date=?",Integer.class,TODAY));
        assertEquals("JOB_FIXTURE",jdbc.queryForObject("SELECT details->'closeConfirmation'->>'source' FROM signal_event WHERE trade_date=?",String.class,TODAY));
    }
    @Test void missingCurrentBarSkipsWithoutEvaluatingCachedData() {
        open(DAY);stock("999612",true);var result=scans.run(DAY,"MANUAL");
        assertEquals("SKIPPED",result.status());assertEquals(1,result.skipped());
        assertEquals("CURRENT_FINAL_BAR_MISSING",jobs.items(result.jobId()).getFirst().get("code"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM signal_event",Integer.class));verifyNoInteractions(advisory);
    }
    @Test void oneProviderFailureDoesNotStopOtherStocksAndIsSanitized() {
        open(DAY);stock("999610",true);stock("999611",true);var result=scans.run(DAY,"MANUAL");
        assertEquals("PARTIAL",result.status());assertEquals(2,result.processed());assertEquals(1,result.failed());assertEquals(1,result.success());
        assertEquals("MARKET_FAILED",jobs.items(result.jobId()).get(1).get("code"));
    }
    @Test void noSignalIsSuccessfulProcessingWithoutModelOrNotification() {
        open(DAY);stock("999613",true);var result=scans.run(DAY,"MANUAL");assertEquals("SUCCESS",result.status());
        assertEquals("NO_SIGNAL",jobs.items(result.jobId()).getFirst().get("code"));verifyNoInteractions(advisory,client);
    }
    @Test void advisoryLockPreventsConcurrentScanAndIsReleased() throws Exception {
        open(DAY);stock("999610",true);
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(76301,1)");
            try {assertEquals("JOB_ALREADY_RUNNING",scans.run(DAY,"MANUAL").code());}
            finally {statement.execute("SELECT pg_advisory_unlock(76301,1)");}
        }
        assertEquals("SUCCESS",scans.run(DAY,"MANUAL").status());
    }
    @Test void interruptedOldRunIsRecoveredOnlyAfterObtainingLock() {
        var old=new JobMapper.Execution();old.date=DAY;old.trigger="MANUAL";jobs.start(old);
        scans.run(DAY,"MANUAL");assertEquals("FAILED",jobs.job(old.id).get("status"));
        assertEquals("PREVIOUS_PROCESS_INTERRUPTED",jobs.job(old.id).get("error_message"));
    }
    @Test void calendarAndConfirmationApisValidateAndRetainOriginalEntry() throws Exception {
        String body="[{\"tradeDate\":\""+TODAY+"\",\"isOpen\":true,\"source\":\"TEST_EXCHANGE_CALENDAR\"}]";
        http.perform(post("/api/v1/calendar/import").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        http.perform(post("/api/v1/calendar/import").contentType(MediaType.APPLICATION_JSON).content(body.replace("true","false"))).andExpect(status().isConflict());
        http.perform(post("/api/v1/market/close-confirmations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"tradeDate\":\""+TODAY+"\",\"source\":\"JOB_FIXTURE\"}")).andExpect(status().isOk());
        http.perform(get("/api/v1/market/close-confirmations?tradeDate="+TODAY)).andExpect(status().isOk());
        http.perform(get("/api/v1/jobs?limit=201")).andExpect(status().isBadRequest());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->scans.run(TODAY.plusDays(1),"MANUAL"));
    }
}
