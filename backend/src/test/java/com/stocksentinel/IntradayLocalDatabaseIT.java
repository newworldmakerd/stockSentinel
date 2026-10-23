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

@SpringBootTest(properties={"pullback.enabled=true","stocksentinel.lock-namespace=76302","personal-price.enabled=true","personal-price.worker-enabled=false","intraday.ma60-enabled=true"})
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
    @Autowired com.stocksentinel.pullback.PullbackService pullback;
    @Autowired com.stocksentinel.pullback.PullbackMapper pullbackMapper;
    @Autowired NotificationService notificationService;
    @Autowired com.stocksentinel.pullback.PullbackDigestMapper digestMapper;
    @Autowired com.stocksentinel.pullback.PullbackDigestQueue digestQueue;
    @Autowired com.stocksentinel.personal.PersonalPriceService personal;
    @Autowired com.stocksentinel.personal.PersonalPriceMapper personalMapper;
    @Autowired com.stocksentinel.personal.PersonalPriceDelivery personalDelivery;
    @Autowired com.stocksentinel.report.DailyReportService reports;
    @Autowired Ma60RiskService ma60Risk;
    void ma60Basis() {
        dates.importCalendar(List.of(new TradingDatePolicy.CalendarEntry(TODAY.minusDays(1),true,"TEST_EXCHANGE_CALENDAR")));
        jdbc.update("INSERT INTO market_daily_bar(symbol,trade_date,open_price,high_price,low_price,close_price,volume,amount,adjustment,source) SELECT '999620',?::date-d,25,25,25,25,1000,100000,'QFQ','fixture' FROM generate_series(0,119) d",TODAY.minusDays(1));
        jdbc.update("INSERT INTO market_daily_bar(symbol,trade_date,open_price,high_price,low_price,close_price,volume,amount,adjustment,source) VALUES('999620',?,100,100,100,100,1000,100000,'NONE','fixture')",TODAY.minusDays(1));
        jdbc.update("INSERT INTO market_indicator_daily(symbol,trade_date,ma5,ma10,ma20,ma60,dif,dea,macd_hist,volume_ratio,calculation_version,adjustment,calculated_at) VALUES('999620',?,25,25,25,25,0,0,0,1,'ma-macd-vol-v1','QFQ',now()+interval '1 second')",TODAY.minusDays(1));
        jdbc.update("INSERT INTO market_screen_run(trade_date,as_of,total) VALUES(?,?,1) ON CONFLICT DO NOTHING",TODAY,TODAY.minusDays(1));
        ma60Quote("100");
    }
    void ma60Quote(String previousClose) {
        jdbc.update("UPDATE market_screen_run SET overview=jsonb_build_object('source','TENCENT_QUOTE','quotes',jsonb_build_array(jsonb_build_object('key','sh999620','previous_close',?::text,'quoted_at',?::text))) WHERE trade_date=?",previousClose,NOW.minusSeconds(10).toString(),TODAY);
    }
    Snapshot ma60Pair(String previous,String current,int shift) {
        var data=snapshot(current,shift);var bars=new ArrayList<>(data.bars());var old=bars.get(bars.size()-2);var p=new BigDecimal(previous);
        bars.set(bars.size()-2,new IntradayContracts.Bar(old.barAt(),p,p.add(new BigDecimal("0.1")),p.subtract(new BigDecimal("0.1")),p,old.volume(),old.amount()));
        var high=bars.subList(0,20).stream().map(IntradayContracts.Bar::highPrice).max(BigDecimal::compareTo).orElseThrow();
        return new Snapshot(data.symbol(),data.source(),data.adjustment(),data.unit(),data.amountUnit(),data.observedAt(),bars,new Metrics(data.metrics().fiveMinuteReturnPct(),high,data.metrics().previousTwentyVolumeMean()));
    }
    @Test void ma60ConvertsPriceUnitsSuppressesInitialHistoryAndOnlyRearmsOnNewCross() throws Exception {
        open();var c=stock("999620");ma60Basis();
        assertTrue(ma60Risk.evaluate(c,ma60Pair("100","99",0)).isEmpty());
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT threshold FROM intraday_ma60_state",BigDecimal.class)));
        assertTrue(ma60Risk.evaluate(c,ma60Pair("99","98",1)).isEmpty());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("98","101",2)).isEmpty());
        var first=ma60Risk.evaluate(c,ma60Pair("100","99",3));assertEquals(1,first.size());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("100","99",3)).isEmpty());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("99","98",4)).isEmpty());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("98","101",5)).isEmpty());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("101","99",6)).isEmpty());
        assertEquals("MA60_CROSS_IN_COOLDOWN",jdbc.queryForObject("SELECT code FROM intraday_ma60_state",String.class));
        assertTrue(ma60Risk.evaluate(c,ma60Pair("99","98",18)).isEmpty());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("98","101",19)).isEmpty());
        assertEquals(1,ma60Risk.evaluate(c,ma60Pair("101","99",20)).size());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE strategy_code='INTRADAY_MA60_V1' AND signal_type='RISK_ALERT'",Integer.class));
        assertEquals(2,count("notify_record"));verify(advisory,times(2)).analyze(anyString());
    }
    @Test void ma60SnapshotAndGroupTemplateClearlySeparateMinuteFromDailyRisk() throws Exception {
        open();var c=stock("999620");ma60Basis();ma60Risk.evaluate(c,ma60Pair("100","101",0));
        var id=ma60Risk.evaluate(c,ma60Pair("100","99",1)).getFirst();
        var details=jdbc.queryForObject("SELECT details->'baseline'->>'ma60Qfq' FROM signal_event WHERE id=?",String.class,id);assertEquals("25.000000",details);
        try(var server=new SmartBotProtocolTest.Bot("SUCCESS")) {
            var wire=server.client();
            try {
                when(client.send(anyString(),anyString())).thenAnswer(call->wire.send(call.getArgument(0),call.getArgument(1)));
                assertTrue(worker.dispatchOne());assertEquals(1,server.sends.get());
                verify(client).send(eq("test-group"),argThat(s->s.contains("盘中跌破MA60")&&s.contains("999620")&&s.contains("日线依据")&&s.contains("不复权分钟收盘价")));
            }finally{wire.destroy();}
        }
    }
    @Test void ma60MissingBasisDoesNotPreventExistingIntradaySignalsAndIsVisible() throws Exception {
        open();dates.importCalendar(List.of(new TradingDatePolicy.CalendarEntry(TODAY.minusDays(1),true,"TEST_EXCHANGE_CALENDAR")));stock("999620");var result=scans.scan("MANUAL");
        assertEquals("SUCCESS",result.status());assertEquals(3,result.signals());
        assertEquals("MA60_DAILY_BASIS_MISSING",jdbc.queryForObject("SELECT code FROM intraday_ma60_state",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE strategy_code='INTRADAY_MA60_V1'",Integer.class));
        http.perform(get("/api/v1/intraday/ma60-risk")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.stocks[0].code").value("MA60_DAILY_BASIS_MISSING"));
    }
    @Test void ma60ReferenceChangeSkipsUntilNewBaselineWithoutRetroactiveCross() {
        open();var c=stock("999620");ma60Basis();ma60Quote("90");
        assertTrue(ma60Risk.evaluate(c,ma60Pair("101","99",0)).isEmpty());
        assertEquals("MA60_CORPORATE_ACTION_OR_REFERENCE_CHANGED",jdbc.queryForObject("SELECT code FROM intraday_ma60_state",String.class));
        ma60Quote("100");assertTrue(ma60Risk.evaluate(c,ma60Pair("101","99",1)).isEmpty());
        assertEquals("MA60_BASELINE_INITIALIZED",jdbc.queryForObject("SELECT code FROM intraday_ma60_state",String.class));assertEquals(0,count("signal_event"));
    }
    @Test void ma60StaleAndMixedSourcesAreRejected() {
        open();var c=stock("999620");ma60Basis();
        jdbc.update("UPDATE market_indicator_daily SET calculated_at=now()-interval '1 day'");
        ma60Risk.evaluate(c,ma60Pair("101","99",0));assertEquals("MA60_STALE_DAILY_INDICATOR",jdbc.queryForObject("SELECT code FROM intraday_ma60_state",String.class));
        jdbc.update("UPDATE market_indicator_daily SET calculated_at=now()+interval '1 second'");
        jdbc.update("UPDATE market_daily_bar SET source='different-source' WHERE adjustment='NONE'");
        ma60Risk.evaluate(c,ma60Pair("101","99",1));assertEquals("MA60_MIXED_DAILY_SOURCE",jdbc.queryForObject("SELECT code FROM intraday_ma60_state",String.class));
        assertEquals(0,count("signal_event"));verifyNoInteractions(advisory,client);
    }
    @Test void ma60ChangedDailyThresholdReseedsInsteadOfProducingArtificialCross() {
        open();var c=stock("999620");ma60Basis();ma60Risk.evaluate(c,ma60Pair("100","101",0));
        jdbc.update("UPDATE market_indicator_daily SET ma60=26,calculated_at=now()+interval '1 second'");
        assertTrue(ma60Risk.evaluate(c,ma60Pair("105","103",1)).isEmpty());
        assertEquals(0,new BigDecimal("104").compareTo(jdbc.queryForObject("SELECT threshold FROM intraday_ma60_state",BigDecimal.class)));
        assertEquals(1,ma60Risk.evaluate(c,ma60Pair("104","103",2)).size());
    }
    @Test void ma60ConcurrentCrossAndRollbackPreserveSignalNotificationAtomicity() throws Exception {
        open();var c=stock("999620");ma60Basis();ma60Risk.evaluate(c,ma60Pair("100","101",0));var crossing=ma60Pair("100","99",1);
        new TransactionTemplate(transactions).executeWithoutResult(s->{ma60Risk.evaluate(c,crossing);s.setRollbackOnly();});
        assertEquals(0,count("signal_event"));assertEquals(0,count("notify_record"));verifyNoInteractions(advisory);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var a=threads.submit(()->ma60Risk.evaluate(c,crossing));var b=threads.submit(()->ma60Risk.evaluate(c,crossing));
            assertEquals(1,a.get(10,TimeUnit.SECONDS).size()+b.get(10,TimeUnit.SECONDS).size());
        }
        assertEquals(1,count("signal_event"));assertEquals(1,count("notify_record"));verify(advisory,times(1)).analyze(anyString());
    }
    @Test void ma60DisabledWatchCannotEmitRisk() {
        open();var c=stock("999620");ma60Basis();ma60Risk.evaluate(c,ma60Pair("100","101",0));
        watchlist.update("999620",false,"test-disabled");assertTrue(ma60Risk.evaluate(c,ma60Pair("100","99",1)).isEmpty());
        assertEquals(0,count("signal_event"));
    }
    @Test void ma60GapDoesNotInventCrossAndBackupSwitchDoesNotRepeat() {
        open();var c=stock("999620");ma60Basis();ma60Risk.evaluate(c,ma60Pair("100","101",0));
        var base=ma60Pair("100","99",1);var before=base.bars().get(base.bars().size()-2);var current=base.bars().getLast();
        var gap=new IntradayContracts.Bar(before.barAt().minusMinutes(1),before.openPrice(),before.highPrice(),before.lowPrice(),before.closePrice(),before.volume(),before.amount());
        var data=new Snapshot(base.symbol(),base.source(),base.adjustment(),base.unit(),base.amountUnit(),base.observedAt(),List.of(gap,current),new Metrics(null,null,null));
        assertTrue(ma60Risk.evaluate(c,data).isEmpty());
        var crossing=ma60Pair("100","99",2);assertEquals(1,ma60Risk.evaluate(c,crossing).size());
        var backup=new Snapshot(crossing.symbol(),"TENCENT_1M",crossing.adjustment(),crossing.unit(),crossing.amountUnit(),crossing.observedAt(),
            crossing.bars().stream().map(b->new IntradayContracts.Bar(b.barAt(),b.openPrice(),b.highPrice(),b.lowPrice(),b.closePrice(),b.volume(),null)).toList(),crossing.metrics());
        assertTrue(ma60Risk.evaluate(c,backup).isEmpty());assertEquals(1,count("signal_event"));
    }
    @Test void ma60WrongDayOrFutureQuoteCannotEnableRisk() {
        open();var c=stock("999620");ma60Basis();
        jdbc.update("UPDATE market_screen_run SET overview=jsonb_set(overview,'{quotes,0,quoted_at}',to_jsonb(?::text))",NOW.plusMinutes(2).toString());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("100","99",0)).isEmpty());
        assertEquals("MA60_CURRENT_REFERENCE_MISSING",jdbc.queryForObject("SELECT code FROM intraday_ma60_state",String.class));
        jdbc.update("UPDATE market_screen_run SET overview=jsonb_set(overview,'{quotes,0,quoted_at}',to_jsonb(?::text))",NOW.minusDays(1).toString());
        assertTrue(ma60Risk.evaluate(c,ma60Pair("100","99",1)).isEmpty());assertEquals(0,count("signal_event"));
    }
    String group(){return reports.destinationHash();}
    int personalEvents(){return jdbc.queryForObject("SELECT count(*) FROM personal_price_event",Integer.class);}
    @Test void personalOwnersHaveIndependentThresholdsAndNeverChangeGlobalPrices() {
        open();var config=stock("999620");
        personal.set(group(),"one","999620",new BigDecimal("101"),new BigDecimal("99"));personal.set(group(),"two","999620",new BigDecimal("103"),new BigDecimal("97"));
        assertEquals(1,personal.evaluate("999620",snapshot("102",0)));assertEquals(0,personal.evaluate("999620",snapshot("102",0)));
        assertTrue(jdbc.queryForObject("SELECT payload FROM personal_price_event LIMIT 1",String.class).contains("所属用户：one"));assertEquals(0,count("signal_event"));assertEquals(0,count("notify_record"));
        String personalPayload=jdbc.queryForObject("SELECT payload FROM personal_price_event LIMIT 1",String.class);
        assertTrue(personalPayload.contains("北京时间"));assertTrue(personalPayload.contains("💰 完整分钟收盘价"));
        assertFalse(personalPayload.contains("Asia/Shanghai"));
        assertEquals(1,personal.evaluate("999620",snapshot("104",1)));assertEquals(2,personal.evaluate("999620",snapshot("97",2)));
        assertEquals(0,personal.evaluate("999620",snapshot("104",3)));assertEquals(4,personalEvents());
        assertEquals(0,config.takeProfit.compareTo(mapper.config("999620").takeProfit));assertEquals(0,config.stopLoss.compareTo(mapper.config("999620").stopLoss));
    }
    @Test void personalPartialUpdatesAreIdempotentAndOnlyChangedRuleRearms() {
        open();stock("999620");var first=personal.set(group(),"one","999620",new BigDecimal("101"),new BigDecimal("99"));
        var repeated=personal.set(group(),"one","999620",new BigDecimal("101"),null);assertEquals(first.revision,repeated.revision);
        personal.evaluate("999620",snapshot("102",0));var changed=personal.set(group(),"one","999620",null,new BigDecimal("98"));
        assertEquals(0,new BigDecimal("101").compareTo(changed.takeProfit));assertEquals(TODAY,changed.takeProfitFiredOn);assertNull(changed.stopLossFiredOn);
        assertEquals(0,personal.evaluate("999620",snapshot("102",1)));
        personal.set(group(),"one","999620",new BigDecimal("101.2"),null);assertEquals(1,personal.evaluate("999620",snapshot("102",2)));
    }
    @Test void personalOnlySymbolIsScannedWithoutCreatingSharedWatchOrSignal() {
        open();market.stocks(List.of(new Stock("999620","personal fixture","SH")));personal.set(group(),"one","999620",new BigDecimal("101"),null);
        var result=scans.scan("MANUAL");assertEquals("SUCCESS",result.status());assertEquals(1,result.signals());assertEquals(1,personalEvents());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));assertEquals(0,count("signal_event"));
        personal.disable(group(),"one","999620");assertEquals("EMPTY_INTRADAY_WATCHLIST",scans.scan("MANUAL").code());
    }
    @Test void personalRejectsInvalidPricesAndDisableIsOwnerScoped() {
        open();stock("999620");personal.set(group(),"one","999620",new BigDecimal("101"),new BigDecimal("99"));personal.set(group(),"two","999620",new BigDecimal("101"),null);
        assertThrows(IllegalArgumentException.class,()->personal.set(group(),"one","999620",null,new BigDecimal("101")));
        assertThrows(IllegalArgumentException.class,()->personal.set(group(),"one","999620",new BigDecimal("101.12345"),null));
        assertThrows(IllegalArgumentException.class,()->personal.set("forged","one","999620",new BigDecimal("101"),null));
        personal.disable(group(),"one","999620");assertEquals(1,personal.evaluate("999620",snapshot("102",0)));assertTrue(jdbc.queryForObject("SELECT payload FROM personal_price_event",String.class).contains("所属用户：two"));
    }
    @Test void personalStaleDataNeverCreatesEventsAndRulesResetNextTradingDay() {
        open();stock("999620");personal.set(group(),"one","999620",new BigDecimal("101"),null);
        var stale=snapshot("102",0);clock.current.set(NOW.plusMinutes(3).toInstant());assertThrows(IntradayValidation.Skip.class,()->personal.evaluate("999620",stale));assertEquals(0,personalEvents());
        personal.evaluate("999620",snapshot("102",0));
        dates.importCalendar(List.of(new TradingDatePolicy.CalendarEntry(TODAY.plusDays(1),true,"TEST_EXCHANGE_CALENDAR")));
        assertEquals(1,personal.evaluate("999620",snapshot("102",1440)));assertEquals(2,personalEvents());
    }
    @Test void personalOutboxUsesSameBotWithOwnerLabelAndAcknowledgedDeduplication() {
        open();stock("999620");personal.set(group(),"one","999620",new BigDecimal("101"),null);personal.evaluate("999620",snapshot("102",0));
        jdbc.update("UPDATE personal_price_event SET bar_at=now()-interval '1 minute'");
        when(client.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"ACK"));
        assertTrue(personalDelivery.dispatchOne());verify(client).send(eq("test-group"),argThat(s->s.contains("所属用户：one")&&s.contains("个人止盈")));
        assertEquals("SUCCESS",jdbc.queryForObject("SELECT status FROM personal_price_event",String.class));assertFalse(personalDelivery.dispatchOne());
    }
    @Test void personalDisabledPendingEventsCancelAndAmbiguousSendsNeverReplay() {
        open();stock("999620");personal.set(group(),"one","999620",new BigDecimal("101"),null);personal.set(group(),"two","999620",new BigDecimal("101"),null);personal.evaluate("999620",snapshot("102",0));
        jdbc.update("UPDATE personal_price_event SET bar_at=now()-interval '1 minute'");personal.disable(group(),"one","999620");
        when(client.send(anyString(),anyString())).thenThrow(new RuntimeException("fixture"));assertTrue(personalDelivery.dispatchOne());assertFalse(personalDelivery.dispatchOne());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM personal_price_event WHERE status='CANCELLED'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM personal_price_event WHERE status='UNKNOWN'",Integer.class));verify(client,times(1)).send(anyString(),anyString());
    }
    @Test void digestSelectionReservationIsProtectedAcrossConnections() throws Exception {
        String hash=UUID.randomUUID().toString();var rendered=new java.util.concurrent.atomic.AtomicBoolean();
        try(var c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?,763014))")) {
                s.setString(1,hash);try(var rs=s.executeQuery()){rs.next();assertTrue(rs.getBoolean(1));}
            }
            assertFalse(digestQueue.enqueue(OffsetDateTime.now(),hash,()->{rendered.set(true);return "fixture";}));assertFalse(rendered.get());
            c.rollback();
        }
        assertTrue(digestQueue.enqueue(OffsetDateTime.now(),hash,()->"fixture"));
    }
    @Test void digestDeduplicatesLegacySentAndReservedNamesPerDayAndDestination() {
        var now=OffsetDateTime.now();var date=now.atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate();String hash=UUID.randomUUID().toString();
        digestMapper.enqueue(now.minusMinutes(10),hash,"fixture\n1. 山东路桥 000498\n正文");
        jdbc.update("UPDATE pullback_digest SET status='SUCCESS' WHERE destination_hash=?",hash);
        assertEquals(List.of("000498"),digestMapper.recommended(date,hash));
        assertTrue(digestMapper.recommended(date.minusDays(1),hash).isEmpty());assertTrue(digestMapper.recommended(date,"another-destination").isEmpty());
        assertTrue(digestQueue.enqueue(now,hash,()->"2. 测试股 600487\n正文"));
        assertTrue(digestMapper.recommended(date,hash).containsAll(List.of("000498","600487")));
        jdbc.update("UPDATE pullback_digest SET status='FAILED' WHERE destination_hash=? AND slot_at=?",hash,now);
        assertEquals(List.of("000498"),digestMapper.recommended(date,hash));
        jdbc.update("UPDATE pullback_digest SET status='UNKNOWN' WHERE destination_hash=?",hash);
        assertTrue(digestMapper.recommended(date,hash).contains("000498"));
    }
    @Test void tenMinuteDigestIsUniqueClaimedOnceAndExpiresOldSlots() {
        var at=OffsetDateTime.now();String hash=UUID.randomUUID().toString();
        assertEquals(1,digestMapper.enqueue(at,hash,"fixture"));assertEquals(0,digestMapper.enqueue(at,hash,"duplicate"));
        var claimed=digestMapper.claim(hash,"first");assertNotNull(claimed);assertNull(digestMapper.claim(hash,"second"));
        assertEquals(0,digestMapper.finish(claimed.id,"wrong","SUCCESS",null));
        assertEquals(1,digestMapper.finish(claimed.id,"first","SUCCESS",null));
        digestMapper.enqueue(at.minusMinutes(11),hash,"old");digestMapper.expire();assertNull(digestMapper.claim(hash,"third"));
    }
    com.stocksentinel.pullback.PullbackMapper.Candidate pullbackCandidate() {
        market.stocks(List.of(new Stock("999620","pullback fixture","SH")));
        dates.importCalendar(List.of(new TradingDatePolicy.CalendarEntry(TODAY.minusDays(1),true,"TEST_EXCHANGE_CALENDAR"),new TradingDatePolicy.CalendarEntry(TODAY,true,"TEST_EXCHANGE_CALENDAR")));
        var row=new com.stocksentinel.pullback.PullbackMapper.Candidate();row.symbol="999620";row.dataDate=TODAY.minusDays(1);
        row.version=pullback.settings().version();row.averageAmount=new BigDecimal("200000000");row.referenceHigh=new BigDecimal("101");row.details="{}";
        pullbackMapper.insert(row);return row;
    }
    @Test void pullbackConfirmsWithoutAddingWatchlistAndNotifiesExactlyOnce() {
        var row=pullbackCandidate();assertEquals(1,pullback.pool().size());
        assertEquals(0,count("signal_event"));assertEquals(0,count("notify_record"));
        assertEquals(1,pullback.confirm(row,snapshot("102",0)).size());
        assertEquals(1,count("signal_event"));assertEquals(1,count("notify_record"));
        assertEquals(0,pullback.confirm(row,snapshot("103",1)).size());
        assertEquals(0,pullback.pool().size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));
        assertTrue(notificationService.claim().payload.contains("缩量回踩"));
    }
    @Test void pullbackRequiresBothPriceAndVolumeAndRejectsExpiredDate() {
        var row=pullbackCandidate();assertTrue(pullback.confirm(row,snapshot("101",0)).isEmpty());
        assertNull(pullbackMapper.lock(row).triggeredAt);
        var original=snapshot("102",1);
        var bars=new ArrayList<>(original.bars());var last=bars.getLast();
        bars.set(bars.size()-1,new IntradayContracts.Bar(last.barAt(),last.openPrice(),last.highPrice(),last.lowPrice(),last.closePrice(),new BigDecimal("1000"),last.amount()));
        var weak=new Snapshot(original.symbol(),original.source(),original.adjustment(),original.unit(),original.amountUnit(),original.observedAt(),bars,original.metrics());
        assertTrue(pullback.confirm(row,weak).isEmpty());assertEquals(0,count("signal_event"));
        row.dataDate=TODAY.minusDays(2);assertTrue(pullback.confirm(row,snapshot("102",2)).isEmpty());
    }
    @Test void pullbackObservationDoesNotCreateSignals() {
        market.stocks(List.of(new Stock("999620","pullback fixture","SH")));
        var bars=PullbackRulesTest.bars(2);var shift=java.time.temporal.ChronoUnit.DAYS.between(bars.getLast().tradeDate,TODAY.minusDays(1));
        bars.forEach(b->{b.tradeDate=b.tradeDate.plusDays(shift);b.symbol="999620";});
        market.bars(bars.stream().map(b->new MarketContracts.Bar("999620",b.tradeDate,b.closePrice,b.highPrice,b.closePrice,b.closePrice,b.volume,new BigDecimal("200000000"),null,Adjustment.QFQ,"fixture",true)).toList());
        var c=PullbackRulesTest.context(bars);
        pullback.observe("999620",TODAY.minusDays(1),new BigDecimal("200000000"),c);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pullback_candidate",Integer.class));assertEquals(0,count("signal_event"));assertEquals(0,count("notify_record"));
    }
    @MockitoBean SmartBotClient client;
    @MockitoBean AdvisoryClient advisory;
    @BeforeEach void setup() throws Exception {
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
        jdbc.execute("TRUNCATE intraday_ma60_state,market_screen_item,market_screen_run,intraday_alert_state,intraday_cursor,market_minute_bar,intraday_monitor,job_execution_item,notify_record,signal_advice,signal_rule_hit,signal_event,market_indicator_daily,market_daily_bar,stock_watchlist,stock_info,market_close_confirmation,market_calendar,job_execution RESTART IDENTITY CASCADE");
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
        open();stock("999621");var result=scans.scan("MANUAL");assertEquals(2,result.signals(),result+" "+jobs.items(result.jobId()));
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
        assertEquals("MINUTE_PRIMARY_INTERNAL_BACKUP_DISABLED",jobs.items(result.jobId()).getFirst().get("code"));verifyNoInteractions(advisory,client);
    }
    @Test void noSignalMeansNoModelOrNotification() {
        open();stock("999624");assertEquals(0,scans.scan("MANUAL").signals());verifyNoInteractions(advisory,client);
    }
    @Test void precisePythonValidationReasonSurvivesHttpAndPersistsWithoutFalseSignals() {
        open();stock("999625");var result=scans.scan("MANUAL");
        assertEquals("FAILED",result.status());
        assertEquals("MINUTE_PRIMARY_DATA_TIME_ORDER_BACKUP_DISABLED",jobs.items(result.jobId()).getFirst().get("code"));
        assertEquals(0,count("signal_event"));assertEquals(0,count("notify_record"));verifyNoInteractions(advisory,client);
    }
    @Test void providerFailureDoesNotBlockFollowingStockOrCreateFalseSignal() {
        open();stock("999623");stock("999624");
        var result=scans.scan("MANUAL");
        assertEquals("PARTIAL",result.status());assertEquals(2,result.processed());assertEquals(1,result.failed());assertEquals(0,result.signals());
        var items=jobs.items(result.jobId());
        assertTrue(items.stream().anyMatch(i->"999623".equals(i.get("symbol"))&&"MINUTE_PRIMARY_INTERNAL_BACKUP_DISABLED".equals(i.get("code"))));
        assertTrue(items.stream().anyMatch(i->"999624".equals(i.get("symbol"))&&"NO_NEW_SIGNAL".equals(i.get("code"))));
        assertEquals(0,count("signal_event"));assertEquals(0,count("notify_record"));verifyNoInteractions(advisory,client);
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
            statement.execute("SELECT pg_advisory_lock(76302,2)");
            try {assertEquals("INTRADAY_ALREADY_RUNNING",scans.scan("MANUAL").code());}
            finally{statement.execute("SELECT pg_advisory_unlock(76302,2)");}
        }
        assertEquals(3,scans.scan("MANUAL").signals());
    }
}
