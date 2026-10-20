package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.advisory.*;
import com.stocksentinel.indicator.*;
import com.stocksentinel.market.*;
import com.stocksentinel.market.MarketContracts.*;
import com.stocksentinel.stock.*;
import com.stocksentinel.strategy.*;
import com.stocksentinel.signal.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.stocksentinel.StrategyFixtures.PARAMS;

/** Own random schema only; proves real commit callbacks without real LLM or vendor calls. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StrategyLocalDatabaseIT.ModelConfig.class)
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class StrategyLocalDatabaseIT {
    private static final String SCHEMA="stocksentinel_task04_"+UUID.randomUUID().toString().replace("-","");
    private static boolean created;
    private static synchronized void prepareSchema() {
        if (created) return;
        if (!SCHEMA.matches("stocksentinel_task04_[a-f0-9]{32}")) throw new IllegalStateException("Invalid isolated schema name");
        try (var connection=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),
                System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
             var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA \""+SCHEMA+"\"");
            created=true;
        } catch (Exception failed) { throw new IllegalStateException("Could not create isolated TASK-04 test schema",failed); }
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
        registry.add("market.enabled",()->false);
        registry.add("llm.enabled",()->false);
        registry.add("llm.model",()->"fixture-model");
    }
    @AfterAll static void dropOwnSchema() throws Exception {
        if (!created) return;
        if (!SCHEMA.matches("stocksentinel_task04_[a-f0-9]{32}")) throw new IllegalStateException("Refusing unsafe schema cleanup");
        try (var connection=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),
                System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
             var statement=connection.createStatement()) {
            statement.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
            created=false;
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ModelConfig {
        @Bean FixedModel fixedModel(JdbcTemplate jdbc,ObjectMapper json) { return new FixedModel(jdbc,json); }
    }
    static class FixedModel implements AdvisoryClient {
        final AtomicInteger calls=new AtomicInteger();
        volatile boolean fail;
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;
        FixedModel(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
        @Override public Advice analyze(String raw) throws Exception {
            calls.incrementAndGet();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"LLM call must not hold database transaction");
            var input=json.readTree(raw);
            long id=input.at("/signal/id").asLong();
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE id=?",Integer.class,id),
                "Signal must be committed before model call");
            if (fail) throw new IllegalStateException("fixture provider failure");
            return new Advice("固定样例建议",List.of("固定规则依据"),List.of("固定样例风险"),"固定条件失效");
        }
    }
    @Autowired FixedModel model;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MarketWriter market;
    @Autowired IndicatorWriter indicators;
    @Autowired StrategyEvaluationService evaluation;
    @Autowired StrategyDefinitionService definitions;
    @Autowired StrategyMapper strategies;
    @Autowired SignalMapper signals;
    @Autowired WatchlistService watchlist;
    @Autowired WatchlistMapper watchlists;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc http;
    private static final AtomicInteger SYMBOLS=new AtomicInteger(999900);
    private String symbol;
    private static final LocalDate START=LocalDate.of(2025,1,1),DATE=START.plusDays(120);
    private AtomicInteger calls;
    @BeforeEach void setup() throws Exception {
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
        symbol=Integer.toString(SYMBOLS.getAndIncrement());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_info WHERE symbol=?",Integer.class,symbol));
        definitions.update("SWING_TREND_V1","1.0.0",true,json.readTree(PARAMS));
        model.calls.set(0); model.fail=false; calls=model.calls;
    }
    void seed(String mode) {
        market.stocks(List.of(new Stock(symbol,"strategy fixture","SH")));
        boolean sell=mode.equals("SELL"),risk=mode.equals("RISK");
        var bars=new java.util.ArrayList<Bar>();
        for (int i=0;i<121;i++) {
            BigDecimal close=BigDecimal.valueOf(i==120 ? (sell ? 100 : risk ? 89 : 110) : 100);
            bars.add(new Bar(symbol,START.plusDays(i),close,close.add(BigDecimal.ONE),close.subtract(BigDecimal.ONE),close,
                BigDecimal.valueOf(i==120 ? 120 : 100),BigDecimal.valueOf(100000),BigDecimal.ONE,Adjustment.QFQ,"fixture",true));
        }
        market.bars(bars);
        BigDecimal reference=BigDecimal.valueOf(220);
        market.bars(List.of(new Bar(symbol,DATE,reference,reference.add(BigDecimal.ONE),reference.subtract(BigDecimal.ONE),reference,
            BigDecimal.valueOf(120),BigDecimal.valueOf(100000),BigDecimal.ONE,Adjustment.NONE,"fixture",true)));
        var previous=new IndicatorContracts.Row(DATE.minusDays(1),BigDecimal.valueOf(104),BigDecimal.valueOf(104),BigDecimal.valueOf(104),
            BigDecimal.valueOf(90),BigDecimal.valueOf(sell ? 2 : 0),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ONE);
        var current=new IndicatorContracts.Row(DATE,BigDecimal.valueOf(105),BigDecimal.valueOf(105),BigDecimal.valueOf(105),
            BigDecimal.valueOf(90),BigDecimal.valueOf(sell ? 0 : mode.equals("NO_SIGNAL") ? 1 : 2),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.valueOf(1.2));
        indicators.save(symbol,Adjustment.QFQ,IndicatorContracts.VERSION,List.of(previous,current));
    }
    StrategyEvaluationService.Result evaluate() { return evaluation.evaluate("SWING_TREND_V1",symbol,DATE); }
    int count(String table) {
        if (!java.util.Set.of("signal_event","signal_advice").contains(table)) throw new IllegalArgumentException();
        return table.equals("signal_event") ? jdbc.queryForObject("SELECT count(*) FROM signal_event WHERE symbol=?",Integer.class,symbol)
            : jdbc.queryForObject("SELECT count(*) FROM signal_advice WHERE signal_id IN (SELECT id FROM signal_event WHERE symbol=?)",Integer.class,symbol);
    }
    @Test void buyPersistsSnapshotsRuleHitsAndAdvisoryExactlyOnce() throws Exception {
        seed("BUY");
        var response=http.perform(post("/api/v1/strategies/SWING_TREND_V1/evaluate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"symbol\":\""+symbol+"\",\"tradeDate\":\""+DATE+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GENERATED")).andReturn().getResponse();
        var first=json.readValue(response.getContentAsString(),StrategyEvaluationService.Result.class);
        assertEquals("GENERATED",first.status()); assertEquals(1,first.signals().size());
        long id=first.signals().getFirst().id();
        var event=signals.find(id);
        assertEquals("BUY_WATCH",event.signalType);
        assertEquals(0,new BigDecimal("220").compareTo(event.referencePrice),"Reference price must be unadjusted");
        assertEquals(4,signals.rulesFor(id).size());
        assertTrue(signals.rulesFor(id).stream().allMatch(rule->rule.passed));
        var snapshot=json.readTree(event.details);
        assertEquals("NONE",snapshot.path("referenceAdjustment").asText());
        assertEquals("QFQ",snapshot.path("indicatorAdjustment").asText());
        assertEquals("ma-macd-vol-v1",snapshot.path("calculationVersion").asText());
        assertEquals("ALREADY_EXISTS",evaluate().status());
        assertEquals(1,count("signal_event")); assertEquals(1,count("signal_advice")); assertEquals(1,calls.get());
        http.perform(get("/api/v1/signals/"+id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.signal.details.referenceAdjustment").value("NONE"))
            .andExpect(jsonPath("$.ruleHits.length()").value(4));
        http.perform(get("/api/v1/signals").param("symbol",symbol).param("type","BUY_WATCH")
            .param("from",DATE.toString()).param("to",DATE.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].symbol").value(symbol));
    }
    @Test void sellAndRiskUseConfirmedRules() {
        seed("SELL");
        assertEquals("SELL_WATCH",evaluate().signals().getFirst().signalType());
        String first=symbol;
        symbol=Integer.toString(SYMBOLS.getAndIncrement()); seed("RISK");
        assertEquals("RISK_ALERT",evaluate().signals().getFirst().signalType());
        assertEquals(1,count("signal_event"));
    }
    @Test void rollbackNeverCallsModelOrLeavesSignalRows() {
        seed("BUY");
        new TransactionTemplate(transactions).execute(status->{
            assertEquals("GENERATED",evaluate().status());
            assertEquals(0,calls.get()); status.setRollbackOnly(); return null;
        });
        assertEquals(0,count("signal_event")); assertEquals(0,count("signal_advice")); assertEquals(0,calls.get());
    }
    @Test void modelFailureDoesNotRollbackCommittedRuleSignal() throws Exception {
        seed("BUY"); model.fail=true;
        var result=evaluate();
        assertEquals("GENERATED",result.status()); assertEquals(1,count("signal_event"));
        assertEquals(1,count("signal_advice"));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM signal_advice WHERE signal_id=?",String.class,result.signals().getFirst().id()));
        assertEquals(4,signals.rulesFor(result.signals().getFirst().id()).size());
    }
    @Test void noMatchedRuleGroupDoesNotPersistOrCallModel() {
        seed("NO_SIGNAL");
        assertEquals("NO_SIGNAL",evaluate().status()); assertEquals(0,count("signal_event")); assertEquals(0,calls.get());
    }
    @Test void staleIndicatorsDoNotGenerateSignals() {
        seed("BUY");
        jdbc.update("UPDATE market_daily_bar SET fetched_at=clock_timestamp()+interval '1 second' WHERE symbol=? AND adjustment='QFQ' AND trade_date=?",symbol,DATE);
        assertEquals("STALE_INDICATORS",evaluate().reason()); assertEquals(0,count("signal_event")); assertEquals(0,calls.get());
    }
    @Test void missingUnadjustedReferenceDoesNotGenerateSignals() {
        seed("BUY");
        jdbc.update("DELETE FROM market_daily_bar WHERE symbol=? AND adjustment='NONE' AND trade_date=?",symbol,DATE);
        assertEquals("MISSING_UNADJUSTED_REFERENCE",evaluate().reason()); assertEquals(0,count("signal_event")); assertEquals(0,calls.get());
    }
    @Test void watchlistIsIdempotentAndDeletingItPreservesStock() throws Exception {
        market.stocks(List.of(new Stock(symbol,"watchlist fixture","SH")));
        http.perform(post("/api/v1/watchlist").contentType(MediaType.APPLICATION_JSON)
            .content("{\"symbol\":\""+symbol+"\",\"remark\":\"original\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.symbol").value(symbol));
        http.perform(put("/api/v1/watchlist/"+symbol).contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false,\"remark\":\"changed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        http.perform(get("/api/v1/watchlist")).andExpect(status().isOk()).andExpect(jsonPath("$[0].symbol").value(symbol));
        var duplicate=watchlist.add(symbol,"ignored");
        assertFalse(duplicate.enabled); assertEquals("changed",duplicate.remark);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM stock_watchlist WHERE symbol=?",Integer.class,symbol));
        http.perform(delete("/api/v1/watchlist/"+symbol)).andExpect(status().isNoContent());
        assertNull(watchlists.find(symbol)); assertNotNull(watchlists.stock(symbol));
    }
    @Test void revisionHistoryIsImmutableAndDisabledStrategyIsSkipped() throws Exception {
        var changed=json.readTree(PARAMS.replace("1.2","1.3"));
        definitions.update("SWING_TREND_V1","1.1.0",true,changed);
        assertEquals("1.0.0",json.readTree(strategies.revisions("SWING_TREND_V1").getFirst()).path("version").asText());
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->definitions.update("SWING_TREND_V1","1.0.0",true,changed)).getStatusCode().value());
        definitions.update("SWING_TREND_V1","1.1.0",false,changed);
        assertEquals("STRATEGY_DISABLED",evaluate().reason()); assertEquals(0,calls.get());
    }
    @Test void concurrentEvaluationDoesNotDuplicateSignalsRulesOrModelCalls() throws Exception {
        seed("BUY");
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(this::evaluate); var second=workers.submit(this::evaluate);
            var results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(result->result.status().equals("GENERATED")).count());
            assertEquals(1,results.stream().filter(result->result.status().equals("ALREADY_EXISTS")).count());
            assertEquals(1,count("signal_event")); assertEquals(1,count("signal_advice")); assertEquals(1,calls.get());
            long id=results.getFirst().signals().getFirst().id(); assertEquals(4,signals.rulesFor(id).size());
        } finally { workers.shutdownNow(); }
    }
}