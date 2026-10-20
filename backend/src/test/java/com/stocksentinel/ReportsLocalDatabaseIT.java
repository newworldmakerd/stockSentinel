package com.stocksentinel;

import com.stocksentinel.report.*;
import com.stocksentinel.jobs.*;
import com.stocksentinel.notification.*;
import com.stocksentinel.market.*;
import com.stocksentinel.indicator.*;
import com.stocksentinel.strategy.*;
import com.stocksentinel.strategy.engine.*;
import com.stocksentinel.common.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"wecom.enabled=true","wecom.bot-id=test-bot","wecom.bot-secret=test-only-credential","wecom.chat-id=test-group",
    "wecom.worker-enabled=false","reports.enabled=true","reports.schedule-enabled=false","reports.worker-enabled=false",
    "screening.enabled=true","screening.llm-enabled=false","llm.enabled=false"})
@Import(ReportsLocalDatabaseIT.TimeConfig.class)
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class ReportsLocalDatabaseIT {
    static final LocalDate TODAY=LocalDate.of(2026,10,20),ASOF=TODAY.minusDays(1);
    @TestConfiguration(proxyBeanMethods=false) static class TimeConfig {
        @Bean @Primary Clock reportClock(){return Clock.fixed(TODAY.atTime(16,0).atZone(TradingDatePolicy.ZONE).toInstant(),TradingDatePolicy.ZONE);}
    }
    static final String SCHEMA="stocksentinel_reports_"+UUID.randomUUID().toString().replace("-","");
    static HttpServer server;
    static java.sql.Connection connection()throws Exception{return DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));}
    @DynamicPropertySource static void prepare(DynamicPropertyRegistry r)throws Exception {
        if(!SCHEMA.matches("stocksentinel_reports_[a-f0-9]{32}"))throw new IllegalStateException();
        try(var c=connection();var s=c.createStatement()){s.execute("CREATE SCHEMA \""+SCHEMA+"\"");}
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/market/overview",e->{e.getRequestBody().readAllBytes();
            var quotes=new ArrayList<Map<String,String>>();for(String symbol:List.of("999620","999621","999622","999623")) quotes.add(Map.of("key","sh"+symbol,"symbol",symbol,"name",symbol.equals("999621")?"ST测试":"测试股","price","102","previous_close","100","volume","10000","quoted_at","2026-10-20T15:00:00+08:00"));
            byte[] bytes=new ObjectMapper().writeValueAsBytes(Map.of("source","TENCENT_QUOTE","observed_at","2026-10-20T16:00:00+08:00","quotes",quotes,"failed_symbols",List.of()));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        r.add("market.python-url",()->"http://127.0.0.1:"+server.getAddress().getPort());
        r.add("spring.datasource.url",()->System.getenv("STOCKSENTINEL_TEST_DB_URL")+"?currentSchema="+SCHEMA);
        r.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));r.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @AfterAll static void cleanup()throws Exception {server.stop(0);if(!SCHEMA.matches("stocksentinel_reports_[a-f0-9]{32}"))throw new IllegalStateException();try(var c=connection();var s=c.createStatement()){s.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}}
    @Autowired JdbcTemplate db;@Autowired ScreeningService screens;@Autowired DailyReportService reports;@Autowired ReportMapper mapper;
    @MockitoBean SmartBotClient bot;@MockitoBean ScreenModel model;@MockitoBean MarketCollectionService collection;
    @MockitoBean IndicatorService indicators;@MockitoBean StrategyContextReader contexts;
    @BeforeEach void seed()throws Exception {
        db.execute("TRUNCATE daily_group_report,market_screen_item,market_screen_run,market_daily_bar,stock_watchlist,stock_info,market_calendar CASCADE");reset(bot,model,collection,indicators,contexts);
        db.update("INSERT INTO market_calendar(trade_date,exchange,is_open,source) VALUES (?,'CN',true,'TEST'),(?,'CN',true,'TEST')",TODAY,ASOF);
        for(String symbol:List.of("999620","999621","999622","999623"))db.update("INSERT INTO stock_info(symbol,name,exchange) VALUES (?,?,'SH')",symbol,"测试股");
        db.update("INSERT INTO stock_watchlist(symbol) VALUES('999620')");
        var day=ASOF;for(int i=0;i<121;i++) {while(day.getDayOfWeek().getValue()>5)day=day.minusDays(1);
            for(String symbol:List.of("999620","999622"))for(String adj:List.of("NONE","QFQ"))
                db.update("INSERT INTO market_daily_bar(symbol,trade_date,open_price,high_price,low_price,close_price,volume,amount,adjustment,source) VALUES(?,?,100,102,99,102,1500,?,?,'TEST_SOURCE')",symbol,day,symbol.equals("999622")?99000000:100000000,adj);
            day=day.minusDays(1);}
        var c=new MarketDailyBar();c.closePrice=new BigDecimal("102");c.volume=new BigDecimal("1500");c.tradeDate=ASOF;
        var p=new MarketDailyBar();p.closePrice=new BigDecimal("100");p.tradeDate=ASOF.minusDays(3);
        var ci=new MarketIndicatorDaily();ci.ma20=new BigDecimal("100");ci.ma60=new BigDecimal("90");ci.dif=BigDecimal.ONE;ci.dea=BigDecimal.ZERO;
        var pi=new MarketIndicatorDaily();pi.ma20=new BigDecimal("99");pi.ma60=new BigDecimal("90");pi.dif=BigDecimal.ONE.negate();pi.dea=BigDecimal.ZERO;
        var params=new StrategyParameters(new BigDecimal("1.2"),MarketContracts.Adjustment.QFQ,"ma-macd-vol-v1","CROSS_BELOW_MA60","DEAD_CROSS_BELOW_MA20");
        when(contexts.read(eq("999620"),eq(ASOF),any())).thenReturn(new StrategyContextReader.Prepared(new StrategyContext(c,p,c,ci,pi,new BigDecimal("5000"),params),null));
        when(model.explain(anyString())).thenReturn("规则已满足；关注MA20失守与缩量失效风险。");
        when(bot.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"ACK_SUCCESS"));
    }
    @Test void fullUniverseUsesRulesLiquidityAndFiltersBeforeModel()throws Exception {
        screens.scan();assertEquals("COMPLETED",mapper.run(TODAY).status);assertEquals(4,mapper.run(TODAY).total);
        assertEquals(1,mapper.candidates(TODAY).size());assertEquals("999620",mapper.candidates(TODAY).getFirst().get("symbol"));
        assertEquals("ST",db.queryForObject("SELECT code FROM market_screen_item WHERE symbol='999621'",String.class));
        assertEquals("LOW_LIQUIDITY",db.queryForObject("SELECT code FROM market_screen_item WHERE symbol='999622'",String.class));
        assertEquals("HISTORY_UNDER_121",db.queryForObject("SELECT code FROM market_screen_item WHERE symbol='999623'",String.class));
        verify(model,times(1)).explain(contains("BUY_WATCH"));assertEquals(1,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM signal_event",Integer.class));
        String markdown=reports.render(TODAY);assertTrue(markdown.contains("2026-10-19"));assertTrue(markdown.contains("模型解释"));assertTrue(markdown.getBytes(StandardCharsets.UTF_8).length<=3900);
    }
    @Test void duplicateEnqueueAndDeliverySendOnlyOneReport() {
        screens.scan();var first=reports.enqueue(TODAY);assertEquals(first.id,reports.enqueue(TODAY).id);
        assertTrue(reports.deliverOne());assertFalse(reports.deliverOne());assertEquals("SUCCESS",mapper.report(TODAY,reports.destinationHash()).status);
        verify(bot,times(1)).send(eq("test-group"),contains("日报"));
    }
    @Test void unknownAckDoesNotRetryAndClosedDayCannotEnqueue() {
        reports.enqueue(TODAY);when(bot.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"ACK_UNCONFIRMED"));
        assertTrue(reports.deliverOne());assertFalse(reports.deliverOne());assertEquals("UNKNOWN",mapper.report(TODAY,reports.destinationHash()).status);
        assertThrows(Exception.class,()->reports.enqueue(TODAY.minusDays(1)));verifyNoInteractions(model);
    }
    @Test void concurrentWorkersClaimReportOnce()throws Exception {
        reports.enqueue(TODAY);try(var pool=Executors.newFixedThreadPool(2)) {var a=pool.submit(reports::deliverOne);var b=pool.submit(reports::deliverOne);assertEquals(1,(a.get()?1:0)+(b.get()?1:0));}
        verify(bot,times(1)).send(anyString(),anyString());
    }
    @Test void modelFailureKeepsRuleCandidateAndLabelsMissingExplanation()throws Exception {
        when(model.explain(anyString())).thenThrow(new java.net.SocketTimeoutException("simulated"));screens.scan();
        assertEquals(1,mapper.candidates(TODAY).size());assertTrue(reports.render(TODAY).contains("模型解释未生成"));
    }
}
