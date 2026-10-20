package com.stocksentinel;

import com.stocksentinel.commands.*;
import com.stocksentinel.notification.*;
import com.stocksentinel.stock.WatchlistMapper;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "wecom.enabled=true","wecom.bot-id=test-bot","wecom.bot-secret=test-only-credential","wecom.chat-id=test-group",
    "wecom.commands.enabled=true","wecom.worker-enabled=false","llm.enabled=false","jobs.schedule-enabled=false","intraday.schedule-enabled=false"
})
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class GroupCommandsLocalDatabaseIT {
    static final String SCHEMA="stocksentinel_commands_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        if(!SCHEMA.matches("stocksentinel_commands_[a-f0-9]{32}")) throw new IllegalStateException();
        try(var c=connection();var s=c.createStatement()) {s.execute("CREATE SCHEMA \""+SCHEMA+"\"");}
        catch(Exception e) {throw new IllegalStateException("Cannot prepare command test schema",e);}
        r.add("spring.datasource.url",()->System.getenv("STOCKSENTINEL_TEST_DB_URL")+"?currentSchema="+SCHEMA);
        r.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));
        r.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
        r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    static java.sql.Connection connection() throws Exception {return DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));}
    @AfterAll static void cleanup() throws Exception {
        if(!SCHEMA.matches("stocksentinel_commands_[a-f0-9]{32}")) throw new IllegalStateException();
        try(var c=connection();var s=c.createStatement()){s.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
    }
    @Autowired GroupMonitorCommands commands;
    @Autowired LoopbackMonitorApi api;
    @Autowired JdbcTemplate db;
    @Autowired WatchlistMapper stocks;
    @MockitoBean MonitorIntentClient intent;
    @MockitoBean SmartBotClient bot;
    @BeforeEach void seed() {
        db.update("DELETE FROM wecom_monitor_command");db.update("DELETE FROM intraday_monitor");db.update("DELETE FROM stock_watchlist");db.update("DELETE FROM stock_info");
        db.update("INSERT INTO stock_info(symbol,name,exchange) VALUES ('000731','四川美丰','SZ'),('600487','亨通光电','SH'),('600001','测试光电','SH'),('920001','北交测试','BJ')");
        reset(intent,bot);when(bot.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"ACK_SUCCESS"));
    }
    GroupMessage message(String id,String text){return new GroupMessage(id,"test-bot","test-group","user-one",text,null);}
    @Test void modelToolCallsRealHttpApiAndDeduplicatesCallback() throws Exception {
        when(intent.stockQuery(anyString())).thenReturn("四川美丰");
        var m=message("unique-one","请监控四川美丰");commands.handle(m);commands.handle(m);
        assertTrue(stocks.find("000731").enabled);
        assertEquals(true,db.queryForObject("SELECT enabled FROM intraday_monitor WHERE symbol='000731'",Boolean.class));
        assertEquals("ADDED",db.queryForObject("SELECT status FROM wecom_monitor_command",String.class));
        assertEquals("SUCCESS",db.queryForObject("SELECT ack_status FROM wecom_monitor_command",String.class));
        verify(intent,times(1)).stockQuery(anyString());verify(bot,times(1)).send(eq("test-group"),contains("四川美丰"));
    }
    @Test void repeatAndReenablePreserveConfiguredThresholds() {
        assertEquals("ADDED",api.add("600487").status());
        db.update("UPDATE intraday_monitor SET enabled=false,rapid_pct=3,take_profit=30,stop_loss=10 WHERE symbol='600487'");
        db.update("UPDATE stock_watchlist SET enabled=false,remark='preserve' WHERE symbol='600487'");
        api.add("600487");assertEquals("ALREADY_MONITORED",api.add("600487").status());
        assertEquals(30,db.queryForObject("SELECT take_profit FROM intraday_monitor WHERE symbol='600487'",Integer.class));
        assertEquals(3,db.queryForObject("SELECT rapid_pct FROM intraday_monitor WHERE symbol='600487'",Integer.class));
        assertEquals("preserve",stocks.find("600487").remark);
    }
    @Test void rejectsOtherGroupsNegationAndStaleMessagesWithoutModel() {
        commands.handle(new GroupMessage("x","test-bot","other-group","u","监控四川美丰",null));
        commands.handle(message("y","不要监控四川美丰"));
        commands.handle(new GroupMessage("z","test-bot","test-group","u","监控四川美丰",1L));
        verifyNoInteractions(intent);assertTrue(stocks.list().isEmpty());
    }
    @Test void hallucinatedStockNeverAdded() throws Exception {
        when(intent.stockQuery(anyString())).thenReturn("600487");commands.handle(message("x","监控四川美丰"));
        assertTrue(stocks.list().isEmpty());assertEquals("FAILED",db.queryForObject("SELECT status FROM wecom_monitor_command",String.class));
    }
    @Test void ambiguousNameRequiresClarification() throws Exception {
        when(intent.stockQuery(anyString())).thenReturn("光电");commands.handle(message("x","监控光电"));
        assertTrue(stocks.list().isEmpty());assertEquals("UNRESOLVED",db.queryForObject("SELECT status FROM wecom_monitor_command",String.class));
    }
    @Test void unsupportedExchangeRollsBackWholeSubscription() {
        assertThrows(Exception.class,()->api.add("920001"));assertNull(stocks.find("920001"));
    }
    @Test void noToolMeansNoMutationOrReply() throws Exception {
        when(intent.stockQuery(anyString())).thenReturn(null);commands.handle(message("x","你关注哪些股票？"));
        assertTrue(stocks.list().isEmpty());verifyNoInteractions(bot);
    }
}

