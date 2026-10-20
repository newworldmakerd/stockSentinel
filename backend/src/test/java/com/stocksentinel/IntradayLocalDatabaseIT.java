package com.stocksentinel;

import com.stocksentinel.jobs.*;
import com.stocksentinel.market.*;
import com.stocksentinel.market.MarketContracts.*;
import com.stocksentinel.stock.*;
import com.stocksentinel.notification.*;
import com.stocksentinel.advisory.*;
import com.stocksentinel.intraday.*;
import com.stocksentinel.intraday.IntradayContracts.*;
import java.math.BigDecimal;
import java.time.*;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(IntradayLocalDatabaseIT.ClockConfig.class)
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class IntradayLocalDatabaseIT {
    static final LocalDate TODAY=LocalDate.now(TradingDatePolicy.ZONE);
    static final OffsetDateTime NOW=TODAY.atTime(10,21,5).atOffset(ZoneOffset.ofHours(8));
    static class MutableClock extends Clock {
        final AtomicReference<Instant> current=new AtomicReference<>(NOW.toInstant());
        @Override public ZoneId getZone(){return TradingDatePolicy.ZONE;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return current.get();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class ClockConfig {
        @Bean @Primary MutableClock testClock(){return new MutableClock();}
    }
    private static final String SCHEMA="stocksentinel_intraday_"+UUID.randomUUID().toString().replace("-","");
    private static boolean created;
    private static synchronized void prepareSchema() {
        if (created) return;
        if (!SCHEMA.matches("stocksentinel_intraday_[a-f0-9]{32}")) throw new IllegalStateException("Invalid isolated schema name");
        try (var connection=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),
                System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
             var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA \""+SCHEMA+"\"");
            created=true;
        } catch (Exception failed) { throw new IllegalStateException("Could not create isolated intraday test schema",failed); }
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
        registry.add("market.python-url",IntradayLocalDatabaseIT::pythonUrl);
        registry.add("jobs.enabled",()->true);
        registry.add("intraday.enabled",()->true);
        registry.add("intraday.schedule-enabled",()->false);
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
        if (!SCHEMA.matches("stocksentinel_intraday_[a-f0-9]{32}")) throw new IllegalStateException("Refusing unsafe schema cleanup");
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
            var builder=new ProcessBuilder(executable.toString(),"quant-service/tests/intraday_fixture_server.py",String.valueOf(port));
            builder.directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("intraday-python-integration.log").toFile());
            builder.environment().keySet().removeIf(key->key.startsWith("POSTGRES_") || key.startsWith("STOCKSENTINEL_TEST_")
                || key.startsWith("LLM_") || key.startsWith("WECOM_") || key.equals("DB_URL"));
            builder.environment().put("MARKET_PROVIDER_ENABLED","false");
            builder.environment().put("INTRADAY_FIXTURE_NOW",NOW.toString());
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
    @Autowired IntradayScanService scans;
    @Autowired IntradayMonitorService monitors;
    @Autowired IntradayMapper mapper;
    @Autowired IntradayWriter writer;
    @Autowired JobMapper jobs;
    @Autowired MarketWriter market;
    @Autowired WatchlistService watchlist;
    @Autowired NotificationWorker worker;
    @Autowired DataSource dataSource;
    @Autowired MockMvc http;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SmartBotClient client;
    @MockitoBean AdvisoryClient advisory;
    @BeforeEach void setup() throws Exception {
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
        jdbc.execute("TRUNCATE intraday_alert_state,intraday_cursor,market_minute_bar,intraday_monitor,job_execution_item,notify_record,signal_advice,signal_rule_hit,signal_event,market_indicator_daily,market_daily_bar,stock_watchlist,stock_info,market_close_confirmation,market_calendar,job_execution RESTART IDENTITY CASCADE");
        clock.current.set(NOW.toInstant());reset(client,advisory);
        when(advisory.analyze(anyString())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new Advice("固定盘中建议",List.of("固定盘中规则"),List.of("行情存在延迟"),"固定失效条件");
        });
    }
    Parameters parameters(BigDecimal take,BigDecimal stop) {
        return new Parameters(true,new BigDecimal("2"),new BigDecimal("1.5"),15,take,stop);
    }
    Config stock(String symbol) {
        market.stocks(List.of(new Stock(symbol,"minute fixture","SH")));watchlist.add(symbol,"fixture");
        return monitors.configure(symbol,parameters(new BigDecimal("101.5"),new BigDecimal("98")));
    }
    void open(){dates.importCalendar(List.of(new TradingDatePolicy.CalendarEntry(TODAY,true,"TEST_EXCHANGE_CALENDAR")));}
    Snapshot snapshot(String price,int shiftMinutes) {
        clock.current.set(NOW.plusMinutes(shiftMinutes).toInstant());
        var base=IntradayRulesTest.snapshot(new BigDecimal(price),new BigDecimal("1500"));
        Duration delta=Duration.between(IntradayRulesTest.NOW,NOW.plusMinutes(shiftMinutes));
        return new Snapshot("999620",base.source(),base.adjustment(),base.unit(),base.amountUnit(),NOW.plusMinutes(shiftMinutes),
            base.bars().stream().map(b->new IntradayContracts.Bar(b.barAt().plus(delta),b.openPrice(),b.highPrice(),b.lowPrice(),b.closePrice(),b.volume(),b.amount())).toList(),base.metrics());
    }
    int count(String table) {
        if(!Set.of("signal_event","notify_record","intraday_cursor","intraday_alert_state","market_minute_bar").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);
    }
    @Test void realMinuteHttpToSignalModelAndSocketNotification() throws Exception {
        open();stock("999620");var result=scans.scan("MANUAL");
        assertEquals("SUCCESS",result.status());assertEquals(3,result.signals());assertEquals(21,count("market_minute_bar"));assertEquals(3,count("notify_record"));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE signal_at IS NOT NULL",Integer.class));
        try(var bot=new SmartBotProtocolTest.Bot("SUCCESS")){var wire=bot.client();try {
            when(client.send(anyString(),anyString())).thenAnswer(call->wire.send(call.getArgument(0),call.getArgument(1)));
            assertTrue(worker.dispatchOne());assertTrue(worker.dispatchOne());assertTrue(worker.dispatchOne());assertEquals(3,bot.sends.get());
        }finally{wire.destroy();}}
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM notify_record WHERE status='SUCCESS'",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT payload FROM notify_record ORDER BY id LIMIT 1",String.class).contains("不复权分钟收盘价"));
        assertTrue(jdbc.queryForObject("SELECT payload FROM notify_record ORDER BY id LIMIT 1",String.class).contains("实际：2；阈值：2"));
        assertEquals(0,scans.scan("MANUAL").signals());assertEquals(3,count("signal_event"));verify(advisory,times(3)).analyze(anyString());
        http.perform(get("/api/v1/signals?type=RAPID_RISE")).andExpect(status().isOk()).andExpect(jsonPath("$[0].signalAt").exists());
    }
    @Test void dropAndStopLossBothTrigger() {
        open();stock("999621");assertEquals(2,scans.scan("MANUAL").signals());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE signal_type='STOP_LOSS'",Integer.class));
    }
    @Test void tencentMissingAmountPersistsWithUnchangedRulesAndSourceSwitchDeduplication() {
        var config=stock("999620");var original=snapshot("102",0);
        var bars=original.bars().stream().map(b->new IntradayContracts.Bar(b.barAt(),b.openPrice(),b.highPrice(),b.lowPrice(),b.closePrice(),b.volume(),null)).toList();
        var backup=new Snapshot(original.symbol(),"TENCENT_1M",original.adjustment(),original.unit(),original.amountUnit(),original.observedAt(),bars,original.metrics());
        assertEquals(3,writer.write(config,backup).size());
        assertEquals(21,jdbc.queryForObject("SELECT count(*) FROM market_minute_bar WHERE source='TENCENT_1M' AND amount IS NULL",Integer.class));
        assertEquals(0,writer.write(config,original).size());
        assertEquals(3,count("signal_event"));assertEquals(3,count("notify_record"));
        assertThrows(Exception.class,()->jdbc.update("UPDATE market_minute_bar SET source='AKSHARE_EASTMONEY_1M' WHERE symbol='999620'"));
    }
    @Test void cooldownAndPriceRearmPersistBetweenMinuteWrites() {
        var config=stock("999620");assertEquals(3,writer.write(config,snapshot("102",0)).size());
        assertEquals(0,writer.write(config,snapshot("102",0)).size());
        assertEquals(0,writer.write(config,snapshot("102",5)).size());
        assertEquals(2,writer.write(config,snapshot("102",15)).size());
        assertEquals(0,writer.write(config,snapshot("100",16)).size());
        assertEquals(1,writer.write(config,snapshot("102",17)).size());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE signal_type='TAKE_PROFIT'",Integer.class));
        assertEquals(6,count("signal_event"));
    }
    @Test void concurrentIdenticalSnapshotCreatesOnlyOneSet() throws Exception {
        var config=stock("999620");var data=snapshot("102",0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->writer.write(config,data));var second=pool.submit(()->writer.write(config,data));
            assertEquals(3,first.get(10,TimeUnit.SECONDS).size()+second.get(10,TimeUnit.SECONDS).size());
        }
        assertEquals(3,count("signal_event"));assertEquals(3,count("notify_record"));
    }
    @Test void transactionRollbackRestoresCursorCooldownSignalsAndNotifications() {
        var config=stock("999620");
        new TransactionTemplate(transactions).executeWithoutResult(s->{writer.write(config,snapshot("102",0));s.setRollbackOnly();});
        assertEquals(0,count("signal_event"));assertEquals(0,count("notify_record"));assertEquals(0,count("intraday_cursor"));assertEquals(0,count("intraday_alert_state"));
        verifyNoInteractions(advisory);assertEquals(3,writer.write(config,snapshot("102",0)).size());
    }
    @Test void staleMinutesNeverProduceSignalsAndOtherStockContinues() {
        open();stock("999620");stock("999622");var result=scans.scan("MANUAL");
        assertEquals("PARTIAL",result.status());assertEquals(3,result.signals());assertEquals(1,result.skipped());
        assertEquals("STALE_MINUTES",jobs.items(result.jobId()).get(1).get("code"));
    }
    @Test void providerFailureIsLoggedWithoutLeakingMessage() {
        open();stock("999623");var result=scans.scan("MANUAL");assertEquals("FAILED",result.status());assertEquals(0,count("signal_event"));
        assertEquals("INTRADAY_PROCESSING_FAILED",jobs.items(result.jobId()).getFirst().get("code"));verifyNoInteractions(advisory,client);
    }
    @Test void noSignalMeansNoModelOrNotification() {
        open();stock("999624");assertEquals(0,scans.scan("MANUAL").signals());verifyNoInteractions(advisory,client);
    }
    @Test void calendarAndLunchGatePreventAnyMinuteWrites() {
        stock("999620");assertEquals("CALENDAR_MISSING",scans.scan("MANUAL").code());
        open();clock.current.set(TODAY.atTime(12,0).atZone(TradingDatePolicy.ZONE).toInstant());
        assertEquals("OUTSIDE_SESSION",scans.scan("MANUAL").code());assertEquals(0,count("market_minute_bar"));
    }
    @Test void disabledWatchlistAndConfigChangesCannotCreateSignal() {
        var config=stock("999620");watchlist.update("999620",false,"disabled");open();assertEquals("EMPTY_INTRADAY_WATCHLIST",scans.scan("MANUAL").code());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->writer.write(config,snapshot("102",0)));
        watchlist.update("999620",true,"enabled");monitors.configure("999620",parameters(new BigDecimal("110"),new BigDecimal("98")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->writer.write(config,snapshot("102",0)));
    }
    @Test void sameParametersKeepRevisionAndApiRejectsUnknownFields() throws Exception {
        var config=stock("999620");assertEquals(config.revision,monitors.configure("999620",config.parameters()).revision);
        String body="{\"enabled\":true,\"rapidPct\":2,\"volumeMultiplier\":1.5,\"cooldownMinutes\":15,\"takeProfit\":105,\"stopLoss\":98}";
        http.perform(put("/api/v1/intraday/monitors/999620").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        http.perform(put("/api/v1/intraday/monitors/999620").contentType(MediaType.APPLICATION_JSON).content(body.replace("\"enabled\"","\"unknown\""))).andExpect(status().isBadRequest());
        http.perform(put("/api/v1/intraday/monitors/999620").contentType(MediaType.APPLICATION_JSON).content(body.replace("\"stopLoss\":98","\"stopLoss\":110"))).andExpect(status().isBadRequest());
    }
    @Test void advisoryLockPreventsDuplicateScan() throws Exception {
        open();stock("999620");
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(76301,2)");
            try {assertEquals("INTRADAY_ALREADY_RUNNING",scans.scan("MANUAL").code());}
            finally{statement.execute("SELECT pg_advisory_unlock(76301,2)");}
        }
        assertEquals(3,scans.scan("MANUAL").signals());
    }
}
