package com.stocksentinel;

import com.stocksentinel.commands.*;
import com.stocksentinel.notification.*;
import com.fasterxml.jackson.databind.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    "wecom.enabled=true","wecom.bot-id=test-bot","wecom.bot-secret=test-only-credential","wecom.chat-id=test-group","wecom.chat-ids=position-second-group",
    "wecom.commands.enabled=true","wecom.commands.chat-enabled=true","wecom.worker-enabled=false","llm.enabled=false","personal-price.enabled=true","personal-price.worker-enabled=false"})
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class GroupAssistantLocalDatabaseIT {
    static final String SCHEMA="stocksentinel_assistant_"+UUID.randomUUID().toString().replace("-","");
    static java.sql.Connection connection()throws Exception{return DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception {
        if(!SCHEMA.matches("stocksentinel_assistant_[a-f0-9]{32}"))throw new IllegalStateException();try(var c=connection();var s=c.createStatement()){s.execute("CREATE SCHEMA \""+SCHEMA+"\"");}
        r.add("spring.datasource.url",()->System.getenv("STOCKSENTINEL_TEST_DB_URL")+"?currentSchema="+SCHEMA);
        r.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));r.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @AfterAll static void cleanup()throws Exception{if(!SCHEMA.matches("stocksentinel_assistant_[a-f0-9]{32}"))throw new IllegalStateException();try(var c=connection();var s=c.createStatement()){s.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}}
    @Autowired GroupMonitorCommands commands;@Autowired GroupAssistantTools tools;@Autowired JdbcTemplate db;
    @Autowired com.stocksentinel.personal.PositionService positions;
    @Autowired com.stocksentinel.personal.PositionDelivery positionDelivery;
    @Autowired com.stocksentinel.notification.NotificationService notifications;
    @Autowired com.stocksentinel.notification.NotificationSettings notificationSettings;
    @Autowired com.stocksentinel.signal.SignalWriter signalWriter;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired MonitorSubscriptionService subscriptions;@Autowired ObjectMapper json;
    @MockitoBean GroupAssistantClient model;@MockitoBean MonitorIntentClient intent;@MockitoBean SmartBotClient bot;
    final String user="user-"+UUID.randomUUID();
    @BeforeEach void seed() {
        db.execute("TRUNCATE stock_info,group_member_alias,wecom_monitor_command CASCADE");
        db.update("INSERT INTO stock_info(symbol,name,exchange) VALUES('000731','四川美丰','SZ'),('600487','亨通光电','SH'),('600001','测试光电','SH'),('920001','北交测试','BJ')");
        reset(model,intent,bot);when(bot.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"ACK"));
    }
    GroupMessage message(String id,String user,String text){return new GroupMessage(id,"test-bot","test-group",user,text,null);}
    JsonNode args(String raw)throws Exception{return json.readTree(raw);}
    JsonNode invoke(GroupMessage m,String tool,String raw)throws Exception{return json.valueToTree(tools.execute(m,tool,args(raw),new AtomicBoolean()));}
    void modelCalls(String name,String raw)throws Exception{when(model.reply(anyList(),any())).thenAnswer(call->{GroupAssistantClient.Tools executor=call.getArgument(1);return json.writeValueAsString(executor.execute(name,args(raw)));});}
    @Test void positionToolsBindToUserAndRequireLiteralCurrentMessageNumbers()throws Exception {
        var m=message("position",user,"记录亨通光电持仓1000股，成本50元，可卖500股");
        var value=invoke(m,"set_position","{\"stock_query\":\"亨通光电\",\"quantity\":1000,\"cost_price\":50,\"sellable_quantity\":500}");
        assertEquals(1000,value.path("quantity").asInt());assertEquals(500,value.path("sellableQuantity").asInt());
        assertEquals("INVALID_REQUEST",invoke(m,"set_position","{\"stock_query\":\"亨通光电\",\"quantity\":1000,\"cost_price\":51}").path("error").asText());
        assertEquals("INVALID_REQUEST",invoke(m,"set_position","{\"stock_query\":\"亨通光电\",\"quantity\":1000,\"cost_price\":50,\"user_id\":\"other\"}").path("error").asText());
        assertTrue(invoke(message("other","other-user","我的仓位"),"list_my_positions","{}").path("positions").isEmpty());
        for(String text:List.of("不要记录亨通光电持仓1000股，成本50元","如果设置亨通光电持仓1000股，成本50元","查看亨通光电持仓1000股，成本50元"))
            assertEquals("INVALID_REQUEST",invoke(message(UUID.randomUUID().toString(),user,text),"set_position","{\"stock_query\":\"亨通光电\",\"quantity\":1000,\"cost_price\":50}").path("error").asText());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));
        invoke(message("clear",user,"记录亨通光电持仓0股"),"set_position","{\"stock_query\":\"亨通光电\",\"quantity\":0}");
        assertEquals(0,db.queryForObject("SELECT quantity FROM personal_position",Integer.class));
    }
    @Test void positionAdviceQueuesOncePerHolderOnlyInTheirGroupAndKeepsPublicSignal()throws Exception {
        String group=notificationSettings.destinationHash("test-group"),other=notificationSettings.destinationHash("position-second-group");
        positions.set(group,user,"600487",1000,new java.math.BigDecimal("50"),500L);
        positions.set(other,"second-user","600487",200,new java.math.BigDecimal("40"),0L);
        long id=db.queryForObject("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason,details) VALUES('600487',CURRENT_DATE,'SWING_TREND_V1','1.0.0','SELL_WATCH',55,'fixture','{}') RETURNING id",Long.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.executeWithoutResult(status->{notifications.enqueue(id);notifications.enqueue(id);});
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notify_record WHERE message_type='PERSONAL_POSITION'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notify_record WHERE message_type='SELL_WATCH'",Integer.class));
        assertTrue(db.queryForObject("SELECT payload FROM notify_record WHERE message_type='PERSONAL_POSITION' AND target_chat_id='test-group'",String.class).contains("可卖500股"));
        assertTrue(db.queryForObject("SELECT payload FROM notify_record WHERE message_type='PERSONAL_POSITION' AND target_chat_id='position-second-group'",String.class).contains("可卖0股"));
        subscriptions.remove("600487");assertEquals(2,db.queryForObject("SELECT count(*) FROM notify_record WHERE message_type='PERSONAL_POSITION' AND status='PENDING'",Integer.class));
        assertTrue(positionDelivery.dispatchOne());assertTrue(positionDelivery.dispatchOne());assertFalse(positionDelivery.dispatchOne());
        verify(bot).send(eq("test-group"),argThat(s->s.contains(user)&&s.contains("可卖500股")&&!s.contains("second-user")));
        verify(bot).send(eq("position-second-group"),argThat(s->s.contains("second-user")&&s.contains("可卖0股")));
        assertEquals("SELL_WATCH",db.queryForObject("SELECT signal_type FROM signal_event WHERE id=?",String.class,id));
        subscriptions.remove("600487");assertEquals(2,db.queryForObject("SELECT count(*) FROM personal_position",Integer.class));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notify_record WHERE message_type='PERSONAL_POSITION' AND status='SUCCESS'",Integer.class));
    }
    @Test void positionWriteSurvivesModelReplyFailureAndReceiptRequestsVerification()throws Exception {
        when(model.reply(anyList(),any())).thenAnswer(call->{GroupAssistantClient.Tools executor=call.getArgument(1);
            executor.execute("set_position",args("{\"stock_query\":\"亨通光电\",\"quantity\":1000,\"cost_price\":50,\"sellable_quantity\":500}"));
            throw new java.net.SocketTimeoutException("fixture");});
        commands.handle(message("write-then-timeout",user,"记录亨通光电持仓1000股，成本50元，可卖500股"));
        assertEquals(1000,db.queryForObject("SELECT quantity FROM personal_position",Integer.class));
        verify(bot).send(eq("test-group"),argThat(s->s.contains("仓位")&&s.contains("核实")));
    }
    @Test void positionDeliveryUnknownIsNotReplayedAndOldAdviceExpires() {
        positions.set(notificationSettings.destinationHash("test-group"),user,"600487",1000,new java.math.BigDecimal("50"),500L);
        long id=db.queryForObject("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason,details) VALUES('600487',CURRENT_DATE,'SWING_TREND_V1','1.0.0','SELL_WATCH',55,'fixture','{}') RETURNING id",Long.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.executeWithoutResult(status->notifications.enqueue(id));
        when(bot.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"NO_ACK"));
        assertTrue(positionDelivery.dispatchOne());assertFalse(positionDelivery.dispatchOne());
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM notify_record WHERE message_type='PERSONAL_POSITION'",String.class));
        positions.set(notificationSettings.destinationHash("test-group"),user,"600487",1000,new java.math.BigDecimal("50"),400L);
        tx.executeWithoutResult(status->notifications.enqueue(id));
        db.update("UPDATE notify_record SET created_at=now()-interval '16 minutes' WHERE message_type='PERSONAL_POSITION' AND status='PENDING'");
        assertFalse(positionDelivery.dispatchOne());assertEquals(1,db.queryForObject("SELECT count(*) FROM notify_record WHERE message_type='PERSONAL_POSITION' AND status='EXPIRED'",Integer.class));
        verify(bot,times(1)).send(anyString(),anyString());
    }
    @Test void missingSellableAndCrossDayAreUnknownAndInvalidUpdatesDoNotOverwrite()throws Exception {
        String group=notificationSettings.destinationHash("test-group");positions.set(group,user,"600487",1000,new java.math.BigDecimal("50"),500L);
        assertThrows(IllegalArgumentException.class,()->positions.set(group,user,"600487",1000,new java.math.BigDecimal("50"),1001L));
        positions.set(group,user,"600487",1000,new java.math.BigDecimal("50"),null);
        assertTrue(invoke(message("q",user,"我的仓位"),"list_my_positions","{}").at("/positions/0/sellableQuantity").isNull());
        db.update("UPDATE personal_position SET sellable_quantity=500,reported_on=CURRENT_DATE-1");
        assertTrue(invoke(message("q2",user,"我的仓位"),"list_my_positions","{}").at("/positions/0/sellableQuantity").isNull());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM personal_position",Integer.class));
    }
    @Test void globalRemovalStopsPublicMonitorRemovesAllOwnersAndKeepsPersonalAndHistory()throws Exception {
        String group=tools.group(message("group",user,"关注亨通光电"));subscriptions.addFromGroup("600487",group,user);
        subscriptions.addFromGroup("600487","a".repeat(64),"other-user");
        invoke(message("price",user,"设置亨通光电止损48元"),"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"stop_loss\":48}");
        db.update("UPDATE intraday_monitor SET rapid_pct=3 WHERE symbol='600487'");
        long signal=db.queryForObject("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason,details) VALUES ('600487',CURRENT_DATE,'INTRADAY_V1','1.0.0','RAPID_RISE',50,'fixture','{}') RETURNING id",Long.class);
        for(String status:List.of("PENDING","RETRY","SUCCESS","UNKNOWN","SENDING"))db.update("INSERT INTO notify_record(signal_id,channel,message_type,idempotency_key,status) VALUES (?,'WECOM_SMART_BOT','RAPID_RISE',?,?)",signal,"remove-"+status,status);
        modelCalls("remove_stock_monitor","{\"stock_query\":\"亨通光电\"}");var command=message("remove",user,"取消关注亨通光电");commands.handle(command);commands.handle(command);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));assertFalse(db.queryForObject("SELECT enabled FROM intraday_monitor WHERE symbol='600487'",Boolean.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM group_watch_owner",Integer.class));assertEquals(1,db.queryForObject("SELECT count(*) FROM personal_price_alert WHERE enabled",Integer.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM signal_event",Integer.class));assertEquals(2,db.queryForObject("SELECT count(*) FROM notify_record WHERE status='CANCELLED'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM notify_record WHERE status='UNKNOWN'",Integer.class));assertEquals(1,db.queryForObject("SELECT count(*) FROM notify_record WHERE status='SENDING'",Integer.class));
        verify(model,times(1)).reply(anyList(),any());verify(bot,times(1)).send(eq("test-group"),contains("REMOVED"));verifyNoInteractions(intent);
        subscriptions.addFromGroup("600487",group,user);assertTrue(db.queryForObject("SELECT enabled FROM intraday_monitor WHERE symbol='600487'",Boolean.class));assertEquals(3,db.queryForObject("SELECT rapid_pct FROM intraday_monitor WHERE symbol='600487'",Integer.class));
    }
    @Test void removalRejectsQuestionsNegationExtraIdentityAndMultipleWrites()throws Exception {
        subscriptions.add("600487");
        for(String text:List.of("可以取消关注亨通光电吗？","不要删除关注亨通光电","取消关注亨通光电和四川美丰","取消关注它"))
            assertEquals("INVALID_REQUEST",invoke(message(UUID.randomUUID().toString(),user,text),"remove_stock_monitor","{\"stock_query\":\"亨通光电\"}").path("error").asText());
        var m=message("delete",user,"删除关注亨通光电");
        assertEquals("INVALID_REQUEST",invoke(m,"remove_stock_monitor","{\"stock_query\":\"亨通光电\",\"user_id\":\"other\"}").path("error").asText());
        var write=new AtomicBoolean();assertEquals("REMOVED",json.valueToTree(tools.execute(m,"remove_stock_monitor",args("{\"stock_query\":\"亨通光电\"}"),write)).path("status").asText());
        assertEquals("INVALID_REQUEST",json.valueToTree(tools.execute(m,"remove_stock_monitor",args("{\"stock_query\":\"亨通光电\"}"),write)).path("error").asText());
    }
    @Test void concurrentAddAndRemoveRemainConsistent()throws Exception {
        String group=tools.group(message("scope",user,"关注亨通光电"));subscriptions.addFromGroup("600487",group,user);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var add=pool.submit(()->{barrier.await();return subscriptions.addFromGroup("600487",group,user);});
            var remove=pool.submit(()->{barrier.await();return subscriptions.remove("600487");});add.get();remove.get();
        }
        int count=db.queryForObject("SELECT count(*) FROM stock_watchlist WHERE symbol='600487'",Integer.class);
        assertEquals(count==1,db.queryForObject("SELECT enabled FROM intraday_monitor WHERE symbol='600487'",Boolean.class));
        assertEquals(count,db.queryForObject("SELECT count(*) FROM group_watch_owner WHERE symbol='600487'",Integer.class));
    }
    @Test void removedWatchCannotCommitSignalFromAnAlreadyStartedDailyJob() {
        subscriptions.add("600487");subscriptions.remove("600487");
        var bar=new com.stocksentinel.common.entity.MarketDailyBar();bar.symbol="600487";
        var context=new com.stocksentinel.strategy.engine.StrategyContext(bar,null,null,null,null,null,null);
        var prepared=new com.stocksentinel.strategy.StrategyContextReader.Prepared(context,null);
        assertThrows(com.stocksentinel.signal.SignalWriter.WatchRemoved.class,()->signalWriter.saveWatched(null,prepared,List.of()));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM signal_event",Integer.class));
    }
    @Test void realHttpRemovalIsIdempotentAndTransactionCanRollBack() {
        subscriptions.add("600487");var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{subscriptions.remove("600487");status.setRollbackOnly();});
        assertEquals(1,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));assertTrue(db.queryForObject("SELECT enabled FROM intraday_monitor WHERE symbol='600487'",Boolean.class));
        for(String expected:List.of("REMOVED","ALREADY_REMOVED")){
            var result=http.exchange("/api/v1/intraday/subscriptions/600487",org.springframework.http.HttpMethod.DELETE,org.springframework.http.HttpEntity.EMPTY,JsonNode.class);
            assertEquals(200,result.getStatusCode().value());assertEquals(expected,result.getBody().path("status").asText());
        }
        assertEquals(0,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));
    }
    @Test void selectionExplanationIsReadOnlyAndBoundToConfiguredGroup()throws Exception {
        var m=message("why",user,"四川美丰为什么没入选？");
        modelCalls("get_selection_explanation","{\"stock_query\":\"四川美丰\"}");commands.handle(m);
        verify(bot).send(eq("test-group"),argThat(s->s.contains("000731")&&s.contains("NOT_STARTED")));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM pullback_candidate",Integer.class));
        assertEquals("INVALID_REQUEST",invoke(m,"get_selection_explanation","{\"stock_query\":\"四川美丰\",\"user_id\":\"other\"}").path("error").asText());
        assertEquals("INVALID_REQUEST",invoke(new GroupMessage("bad","test-bot","unconfigured",user,"解释四川美丰",null),"get_selection_explanation","{\"stock_query\":\"四川美丰\"}").path("error").asText());
        verifyNoInteractions(intent);
    }
    @Test void conversationAddsAttributedFollowOnceAndMultiplePeopleCanFollow()throws Exception {
        when(intent.stockQuery(anyString())).thenReturn("四川美丰");modelCalls("add_stock_monitor","{\"stock_query\":\"四川美丰\"}");
        var first=message("a",user,"监控四川美丰");commands.handle(first);commands.handle(first);
        commands.handle(message("b","second-user","关注四川美丰"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));assertEquals(2,db.queryForObject("SELECT count(*) FROM group_watch_owner",Integer.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM group_watch_owner WHERE user_id=?",Integer.class,user));verify(model,times(2)).reply(anyList(),any());
        var mine=invoke(first,"list_watchlist","{\"scope\":\"mine\"}");assertEquals("000731",mine.at("/stocks/0/symbol").asText());assertEquals(2,mine.at("/stocks/0/followers").size());
        var other=invoke(message("c","third-user","我的关注"),"list_watchlist","{\"scope\":\"mine\"}");assertTrue(other.path("stocks").isEmpty());
    }
    @Test void legacyOwnershipIsUnknownAndReadRequestsNeverWrite()throws Exception {
        subscriptions.add("600487");var m=message("a",user,"查看本群关注列表");modelCalls("list_watchlist","{\"scope\":\"group\"}");commands.handle(m);
        var group=invoke(m,"list_watchlist","{\"scope\":\"group\"}");assertTrue(group.at("/stocks/0/origin").asText().contains("来源未记录"));assertEquals(0,db.queryForObject("SELECT count(*) FROM group_watch_owner",Integer.class));
        verify(bot).send(eq("test-group"),argThat(s->s.contains("亨通光电")&&s.contains("600487")));verifyNoInteractions(intent);
    }
    @Test void readQuestionsOrForgedIdentityCannotAuthorizeWrites()throws Exception {
        when(intent.stockQuery(anyString())).thenReturn("四川美丰");
        var m=message("a",user,"谁关注了四川美丰？");assertEquals("INVALID_REQUEST",invoke(m,"add_stock_monitor","{\"stock_query\":\"四川美丰\"}").path("error").asText());
        assertEquals("INVALID_REQUEST",invoke(m,"list_watchlist","{\"scope\":\"mine\",\"user_id\":\"other\"}").path("error").asText());
        assertEquals("UNSUPPORTED_TOOL",invoke(m,"execute_sql","{}").path("error").asText());assertEquals(0,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));
    }
    @Test void ownAliasCannotChangeAnotherUserAndDoesNotInventDirectoryIdentity()throws Exception {
        var m=message("a",user,"叫我小刘");assertEquals("SAVED",invoke(m,"set_my_name","{\"name\":\"小刘\"}").path("status").asText());
        assertEquals(user,db.queryForObject("SELECT user_id FROM group_member_alias",String.class));
        assertEquals("小刘",invoke(m,"list_watchlist","{\"scope\":\"mine\"}").at("/requester/displayName").asText());
        assertEquals("INVALID_REQUEST",invoke(m,"set_my_name","{\"name\":\"小刘\",\"user_id\":\"someone-else\"}").path("error").asText());
    }
    @Test void personalPriceToolsUseLiteralNumbersAndCurrentUserOnly()throws Exception {
        var m=message("prices",user,"设置亨通光电止盈60元，止损48元");
        var configured=invoke(m,"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"take_profit\":60,\"stop_loss\":48}");assertEquals("600487",configured.path("symbol").asText());
        assertEquals(user,db.queryForObject("SELECT user_id FROM personal_price_alert",String.class));assertEquals(0,db.queryForObject("SELECT count(*) FROM intraday_monitor",Integer.class));
        var other=invoke(message("other","someone-else","我的价位提醒"),"list_my_price_alerts","{}");assertTrue(other.path("alerts").isEmpty());
        assertEquals("INVALID_REQUEST",invoke(m,"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"take_profit\":61}").path("error").asText());
        assertEquals("INVALID_REQUEST",invoke(m,"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"take_profit\":60,\"user_id\":\"victim\"}").path("error").asText());
        assertEquals("INVALID_REQUEST",invoke(message("q",user,"查看亨通光电止盈60元"),"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"take_profit\":60}").path("error").asText());
    }
    @Test void personalPriceRangesAreNotGuessedAndExplicitDisableKeepsOthers()throws Exception {
        assertEquals("INVALID_REQUEST",invoke(message("range",user,"设置亨通光电止盈60至62元"),"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"take_profit\":60}").path("error").asText());
        invoke(message("p",user,"设置亨通光电止盈60元"),"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"take_profit\":60}");
        invoke(message("p2","another-user","设置亨通光电止盈62元"),"set_personal_price_alert","{\"stock_query\":\"亨通光电\",\"take_profit\":62}");
        var result=invoke(message("off",user,"关闭亨通光电的个人价位提醒"),"disable_personal_price_alert","{\"stock_query\":\"亨通光电\"}");assertEquals("DISABLED",result.path("status").asText());assertEquals(1,db.queryForObject("SELECT count(*) FROM personal_price_alert WHERE enabled",Integer.class));
    }
    @Test void unsupportedStockRollsBackFollowAndWatchTogether()throws Exception {
        when(intent.stockQuery(anyString())).thenReturn("920001");var m=message("a",user,"监控920001");assertEquals("TOOL_UNAVAILABLE",invoke(m,"add_stock_monitor","{\"stock_query\":\"920001\"}").path("error").asText());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM stock_watchlist",Integer.class));assertEquals(0,db.queryForObject("SELECT count(*) FROM group_watch_owner",Integer.class));
    }
    @Test void generalStrategyQuestionIsAnsweredAndOtherGroupsAreIgnored()throws Exception {
        modelCalls("get_strategy_info","{}");commands.handle(message("a",user,"不要修改，请解释策略"));verify(bot).send(eq("test-group"),argThat(s->s.contains("MA20")));
        commands.handle(new GroupMessage("other","test-bot","another-group",user,"解释策略",null));verify(model,times(1)).reply(anyList(),any());
    }
}
