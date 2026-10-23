package com.stocksentinel;

import com.stocksentinel.personal.*;
import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.notification.*;
import com.stocksentinel.commands.*;
import java.sql.DriverManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"wecom.enabled=true","wecom.bot-id=test-bot","wecom.bot-secret=test-only-credential","wecom.chat-id=test-group","wecom.chat-ids=second-group","wecom.worker-enabled=false","t-observation.enabled=true","llm.enabled=false"})
@Import(TObservationLocalDatabaseIT.TimeConfig.class)
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class TObservationLocalDatabaseIT {
    static final OffsetDateTime NOW=OffsetDateTime.parse("2026-10-21T10:21:05+08:00");
    static class MutableClock extends Clock {Instant value=NOW.toInstant();public ZoneId getZone(){return TradingDatePolicy.ZONE;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}}
    @TestConfiguration(proxyBeanMethods=false) static class TimeConfig {@Bean @Primary MutableClock tClock(){return new MutableClock();}}
    static final String SCHEMA="stocksentinel_t_"+UUID.randomUUID().toString().replace("-","");
    static java.sql.Connection connection()throws Exception{return DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception{
        if(!SCHEMA.matches("stocksentinel_t_[a-f0-9]{32}"))throw new IllegalStateException();
        try(var c=connection();var s=c.createStatement()){s.execute("CREATE SCHEMA \""+SCHEMA+"\"");}
        r.add("spring.datasource.url",()->System.getenv("STOCKSENTINEL_TEST_DB_URL")+"?currentSchema="+SCHEMA);
        r.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));r.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @AfterAll static void cleanup()throws Exception{if(!SCHEMA.matches("stocksentinel_t_[a-f0-9]{32}"))throw new IllegalStateException();try(var c=connection();var s=c.createStatement()){s.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}}
    @Autowired JdbcTemplate db;@Autowired TObservationService service;@Autowired TObservationMapper mapper;
    @Autowired PositionService positions;@Autowired NotificationSettings settings;@Autowired MutableClock clock;@Autowired GroupAssistantTools tools;
    @Autowired com.stocksentinel.monitoring.ScanTelemetry telemetry;
    @Autowired com.stocksentinel.monitoring.ScanTelemetryMapper telemetryMapper;
    @Autowired com.stocksentinel.monitoring.ScanDiagnosticsController diagnostics;
    @MockitoBean SmartBotClient bot;
    String group(){return settings.destinationHash("test-group");}
    @BeforeEach void seed(){clock.value=NOW.toInstant();db.execute("TRUNCATE stock_info,market_calendar,notify_record CASCADE");db.update("INSERT INTO stock_info(symbol,name,exchange) VALUES('000731','四川美丰','SZ')");db.update("INSERT INTO market_calendar(trade_date,exchange,is_open,source) VALUES('2026-10-21','CN',true,'TEST')");positions.set(group(),"u1","000731",800,new BigDecimal("7.8"),800L);}
    Snapshot minutes(){var b=new Bar(NOW.withMinute(20).withSecond(0),new BigDecimal("8"),new BigDecimal("8"),new BigDecimal("8"),new BigDecimal("8"),new BigDecimal("100"),new BigDecimal("800"));return new Snapshot("000731","TEST","NONE","SHARE","CNY",NOW,List.of(b),new Metrics(null,null,null));}
    TObservationRules.Rsi rsi(String five,String fifteen){return new TObservationRules.Rsi("000731","AKSHARE_EASTMONEY_5M","QFQ",14,NOW,NOW.withMinute(20).withSecond(0),NOW.withMinute(15).withSecond(0),new BigDecimal(five),new BigDecimal(fifteen));}
    @Test void heldSymbolsIncludeUnwatchedButExcludeUnknownSellableAndIsolateGroups(){
        positions.set(group(),"unknown","000731",800,new BigDecimal("7.8"),null);
        positions.set(settings.destinationHash("second-group"),"u2","000731",500,new BigDecimal("7.8"),500L);
        assertEquals(List.of("000731"),service.symbols());assertEquals(2,service.evaluate("000731",minutes(),rsi("81","51")));
        assertEquals(0,service.evaluate("000731",minutes(),rsi("81","51")));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notify_record WHERE message_type='T_OBSERVATION'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT count(DISTINCT target_chat_id) FROM notify_record",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));
        assertTrue(db.queryForObject("SELECT payload FROM notify_record WHERE target_chat_id='test-group'",String.class).contains("四川美丰 000731"));
        String payload=db.queryForObject("SELECT payload FROM notify_record WHERE target_chat_id='test-group'",String.class);
        assertTrue(payload.contains("📍 观察说明"));assertTrue(payload.contains("RSI：5分钟=81.00，15分钟=51.00"));
        assertTrue(payload.contains("2026-10-21 10:20"));assertTrue(payload.contains("北京时间"));
        assertFalse(payload.contains("Asia/Shanghai"));assertFalse(payload.contains("AKSHARE_EASTMONEY_5M"));
    }
    @Test void concurrentScansEnqueueOnlyOneAndSameDirectionNeverRepeats(){
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->service.evaluate("000731",minutes(),rsi("81","51")));var b=pool.submit(()->service.evaluate("000731",minutes(),rsi("81","51")));
            try{assertEquals(1,a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS));}catch(Exception e){throw new RuntimeException(e);}
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM notify_record",Integer.class));
    }
    @Test void actualFillsAreIdempotentCappedAndRestoreDoesNotRequireMarketData(){
        service.evaluate("000731",minutes(),rsi("81","51"));
        assertThrows(IllegalArgumentException.class,()->service.fill(group(),"u1","000731","SELL",801,new BigDecimal("8"),"oversize"));
        service.fill(group(),"u1","000731","SELL",200,new BigDecimal("8"),"one");
        service.fill(group(),"u1","000731","SELL",200,new BigDecimal("8"),"one");
        service.fill(group(),"u1","000731","SELL",100,new BigDecimal("8"),"two");
        assertThrows(IllegalArgumentException.class,()->service.fill(group(),"u1","000731","BUY",301,new BigDecimal("7.8"),"too-much"));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM t_trade_event",Integer.class));
        assertEquals(800,db.queryForObject("SELECT quantity FROM personal_position",Integer.class));
        clock.value=OffsetDateTime.parse("2026-10-21T14:50:00+08:00").toInstant();assertEquals(1,service.restore());assertEquals(0,service.restore());
        assertTrue(db.queryForObject("SELECT payload FROM notify_record WHERE payload LIKE '%收盘恢复底仓%'",String.class).contains("少 300 股"));
        service.fill(group(),"u1","000731","BUY",300,new BigDecimal("7.8"),"buyback");assertEquals(0,service.restore());
    }
    @Test void noInventedFillsAndUpdatedHoldingsPauseAutomaticObservations(){
        assertThrows(IllegalArgumentException.class,()->service.fill(group(),"u1","000731","SELL",100,new BigDecimal("8"),"early"));
        assertEquals(0,service.evaluate("000731",minutes(),rsi("55","55")));
        positions.set(group(),"u1","000731",600,new BigDecimal("7.8"),600L);
        assertEquals(0,service.evaluate("000731",minutes(),rsi("81","51")));
        assertEquals("POSITION_CHANGED_PAUSED",mapper.get(group(),"u1","000731",NOW.toLocalDate()).lastCode);
        var message=new GroupMessage("no","test-bot","test-group","u1","不要记录四川美丰做T卖出成交200股，成交价8元",null);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var args=json.createObjectNode().put("stock_query","四川美丰").put("side","SELL").put("quantity",200).put("price",8);
        assertTrue(tools.execute(message,"record_t_fill",args,new AtomicBoolean()).toString().contains("INVALID_REQUEST"));
    }
    @Test void unknownUserCannotReadOrWriteSomeoneElsesStateAndStaleDataFailsClosed(){
        service.evaluate("000731",minutes(),rsi("81","51"));assertNull(mapper.get(group(),"other","000731",NOW.toLocalDate()));
        assertThrows(IllegalArgumentException.class,()->service.fill(group(),"other","000731","SELL",100,new BigDecimal("8"),"spoof"));
        assertThrows(com.stocksentinel.intraday.IntradayValidation.Skip.class,()->service.evaluate("000731",minutes(),new TObservationRules.Rsi("000731","AKSHARE_EASTMONEY_5M","QFQ",14,NOW.minusHours(1),NOW.minusHours(1),NOW.minusHours(1),BigDecimal.TEN,BigDecimal.TEN)));
        Long job=db.queryForObject("INSERT INTO job_execution(job_name,status,trade_date,trigger_type) VALUES('INTRADAY_SCAN','FAILED','2026-10-21','MANUAL') RETURNING id",Long.class);
        db.update("INSERT INTO job_execution_item(job_id,symbol,stage,status,code) VALUES(?,'000731','INTRADAY','FAILED','T_RSI_HTTP_422')",job);
        @SuppressWarnings("unchecked") var status=(Map<String,Object>)service.status(group(),"u1","000731");
        assertTrue(status.get("latestIntradayScan").toString().contains("T_RSI_HTTP_422"));
    }
    @Test void lowResonanceMayStartWithBuyAndOnlyKnownNetIsRestored(){
        assertEquals(1,service.evaluate("000731",minutes(),rsi("19","49")));
        assertTrue(db.queryForObject("SELECT payload FROM notify_record",String.class).contains("不给出买入股数"));
        service.fill(group(),"u1","000731","BUY",200,new BigDecimal("7.6"),"low-buy");
        clock.value=OffsetDateTime.parse("2026-10-21T14:50:00+08:00").toInstant();assertEquals(1,service.restore());
        assertTrue(db.queryForObject("SELECT payload FROM notify_record WHERE payload LIKE '%收盘恢复底仓%'",String.class).contains("多 200 股"));
        service.fill(group(),"u1","000731","SELL",200,new BigDecimal("7.7"),"restore-sell");
        assertEquals(0,service.restore());assertEquals(800,db.queryForObject("SELECT quantity FROM personal_position",Integer.class));
    }
    @Test void diagnosticTracePersistsFourTimesAndDoesNotExposePrivateHoldings(){
        Long job=db.queryForObject("INSERT INTO job_execution(job_name,status,trade_date,trigger_type) VALUES('INTRADAY_SCAN','RUNNING','2026-10-21','MANUAL') RETURNING id",Long.class);
        telemetry.record(job,"000731","MINUTE_FETCH","SUCCESS","COMPLETED",NOW,NOW,12,minutes());
        assertEquals(1,telemetryMapper.steps(job).size());assertEquals("TEST",telemetryMapper.steps(job).getFirst().get("source"));
        assertEquals(1,service.evaluate("000731",minutes(),rsi("81","51"),job));
        Long id=db.queryForObject("SELECT id FROM notify_record",Long.class);var trace=telemetryMapper.trace(id);
        assertEquals(job,trace.get("observation_job_id"));assertNotNull(trace.get("queued_at"));assertNotNull(trace.get("evaluated_at"));assertNotNull(trace.get("indicator_observed_at"));
        for(String key:List.of("payload","target_chat_id","user_id","group_hash","quantity","cost_price","idempotency_key"))assertFalse(trace.containsKey(key));
        assertDoesNotThrow(()->diagnostics.steps(job));assertDoesNotThrow(()->diagnostics.trace(id));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->diagnostics.steps(Long.MAX_VALUE));
        db.update("UPDATE notify_record SET last_error='private callback text',status='FAILED' WHERE id=?",id);
        assertEquals("DELIVERY_FAILURE",telemetryMapper.trace(id).get("delivery_code"));
    }
    @Test void partialFillsCorrectionsVoidsAndWeightedPriceAreAuditable(){
        service.evaluate("000731",minutes(),rsi("81","51"));
        @SuppressWarnings("unchecked") var first=(Map<String,Object>)service.fill(group(),"u1","000731","SELL",100,new BigDecimal("8"),"sell-a");
        @SuppressWarnings("unchecked") var second=(Map<String,Object>)service.fill(group(),"u1","000731","SELL",100,new BigDecimal("10"),"sell-b");
        service.fill(group(),"u1","000731","BUY",50,new BigDecimal("7.8"),"buy-a");
        var d=mapper.get(group(),"u1","000731",NOW.toLocalDate());assertEquals(200L,d.sellFilled);assertEquals(50L,d.buyFilled);assertEquals(0,d.sellPrice.compareTo(new BigDecimal("9")));
        long a=((Number)first.get("eventId")).longValue(),b=((Number)second.get("eventId")).longValue();
        service.amend(group(),"u1",a,false,50L,new BigDecimal("8"),"correct-a");
        assertEquals(150L,mapper.get(group(),"u1","000731",NOW.toLocalDate()).sellFilled);
        service.amend(group(),"u1",b,true,null,null,"void-b");
        assertEquals(50L,mapper.get(group(),"u1","000731",NOW.toLocalDate()).sellFilled);
        assertEquals(100,db.queryForObject("SELECT quantity FROM t_trade_event WHERE id=?",Integer.class,a));assertEquals(5,db.queryForObject("SELECT count(*) FROM t_trade_event",Integer.class));
        service.amend(group(),"u1",b,true,null,null,"void-b");assertEquals(5,db.queryForObject("SELECT count(*) FROM t_trade_event",Integer.class));
        assertThrows(IllegalArgumentException.class,()->service.amend(group(),"other",a,true,null,null,"wrong-owner"));
        assertThrows(IllegalArgumentException.class,()->service.amend(group(),"u1",a,false,900L,new BigDecimal("8"),"bad-correction"));assertEquals(5,db.queryForObject("SELECT count(*) FROM t_trade_event",Integer.class));
    }
    @Test void resumeRequiresReconciledQuantityAndDoesNotMakeTodaysBuysSellable(){
        service.evaluate("000731",minutes(),rsi("81","51"));service.fill(group(),"u1","000731","SELL",200,new BigDecimal("8"),"sell");service.fill(group(),"u1","000731","BUY",50,new BigDecimal("7.8"),"buy");
        positions.set(group(),"u1","000731",700,new BigDecimal("7.8"),600L);assertThrows(IllegalArgumentException.class,()->service.reconcile(group(),"u1","000731"));
        positions.set(group(),"u1","000731",650,new BigDecimal("7.8"),650L);assertThrows(IllegalArgumentException.class,()->service.reconcile(group(),"u1","000731"));
        positions.set(group(),"u1","000731",650,new BigDecimal("7.8"),600L);assertDoesNotThrow(()->service.reconcile(group(),"u1","000731"));
        var d=mapper.get(group(),"u1","000731",NOW.toLocalDate());assertEquals(800L,d.baselineQuantity);assertTrue(d.sellObserved);assertEquals(200L,d.sellFilled);assertEquals("BASELINE_RECONCILED",d.lastCode);
    }
}
