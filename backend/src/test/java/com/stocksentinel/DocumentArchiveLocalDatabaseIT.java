package com.stocksentinel;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.document.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

@SpringBootTest(properties={"wecom-doc.enabled=true","wecom-doc.mcp-url=https://qyapi.weixin.qq.com/test-mcp","wecom-doc.schedule-enabled=false","llm.enabled=false"})
@Import(DocumentArchiveLocalDatabaseIT.TimeConfig.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class DocumentArchiveLocalDatabaseIT {
    static final LocalDate DAY=LocalDate.of(2026,10,22);
    @TestConfiguration(proxyBeanMethods=false) static class TimeConfig {@Bean @Primary Clock documentClock(){return Clock.fixed(OffsetDateTime.parse("2026-10-22T16:00:00+08:00").toInstant(),TradingDatePolicy.ZONE);}}
    static final String SCHEMA="stocksentinel_doc_"+UUID.randomUUID().toString().replace("-","");
    static Connection connection()throws Exception{return DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception{
        if(!SCHEMA.matches("stocksentinel_doc_[a-f0-9]{32}"))throw new IllegalStateException();
        try(var c=connection();var s=c.createStatement()){s.execute("CREATE SCHEMA \""+SCHEMA+"\"");}
        r.add("spring.datasource.url",()->System.getenv("STOCKSENTINEL_TEST_DB_URL")+"?currentSchema="+SCHEMA);r.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));r.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @AfterAll static void cleanup()throws Exception{if(!SCHEMA.matches("stocksentinel_doc_[a-f0-9]{32}"))throw new IllegalStateException();try(var c=connection();var s=c.createStatement()){s.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}}
    @Autowired DocumentArchiveService service;@Autowired DocumentArchiveMapper mapper;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @MockitoBean DocumentMcpClient client;
    DocumentMcpClient.Session session;final AtomicInteger fieldReads=new AtomicInteger(),recordIds=new AtomicInteger();
    JsonNode fields(boolean full){var node=json.createObjectNode();var array=node.putArray("fields");for(String field:full?StockDocumentSnapshot.FIELDS:List.of("文本")){array.addObject().put("field_id","f-"+field).put("field_title",field).put("field_type","FIELD_TYPE_TEXT");}return node;}
    @BeforeEach void seed(){
        db.execute("TRUNCATE stock_info,market_calendar,daily_group_report,wecom_document_export CASCADE");db.update("UPDATE wecom_document_target SET status='NEW',doc_id=NULL,doc_url=NULL,sheet_id=NULL,last_error=NULL");
        db.update("INSERT INTO market_calendar(trade_date,exchange,is_open,source) VALUES(?,'CN',true,'TEST')",DAY);
        db.update("INSERT INTO stock_info(symbol,name,exchange) VALUES('000731','四川美丰','SZ')");db.update("INSERT INTO stock_watchlist(symbol) VALUES('000731')");
        db.update("INSERT INTO daily_group_report(trade_date,destination_hash,payload) VALUES(?,repeat('a',64),'frozen test report')",DAY);
        reset(client);session=mock(DocumentMcpClient.Session.class);fieldReads.set(0);recordIds.set(0);when(client.open()).thenReturn(session);
        when(session.call(anyString(),anyMap())).thenAnswer(invocation->{String tool=invocation.getArgument(0);Map<String,Object> args=invocation.getArgument(1);
            return switch(tool){case "create_doc"->json.valueToTree(Map.of("errcode",0,"docid","test-doc","url","https://doc.weixin.qq.com/smartsheet/test"));
                case "smartsheet_get_sheet"->json.valueToTree(Map.of("sheets",List.of(Map.of("sheet_id","sheet1"))));
                case "smartsheet_get_fields"->fields(fieldReads.incrementAndGet()>1);
                case "smartsheet_add_records"->{var r=json.createObjectNode();var ids=r.putArray("records");for(var item:(List<?>)args.get("records"))ids.addObject().put("record_id","record-"+recordIds.incrementAndGet());yield r;}
                default->json.valueToTree(Map.of("errcode",0));};
        });
    }
    JsonNode view(Object value){return json.valueToTree(value);}
    void setup(){assertEquals("READY",view(service.setup(false)).path("status").asText());}
    @Test void dryRunNeverInitializesOrWritesRemote(){assertTrue(view(service.setup(true)).path("dryRun").asBoolean());assertEquals(1,view(service.preview(DAY)).path("records").size());assertEquals("NEW",mapper.target().status);verifyNoInteractions(client);}
    @Test void setupRenamesDefaultColumnAndIsIdempotent(){setup();service.setup(false);verify(session,times(1)).call(eq("create_doc"),anyMap());verify(session,times(1)).call(eq("smartsheet_update_fields"),anyMap());assertEquals("test-doc",mapper.target().docId);assertFalse(view(service.status()).toString().contains("test-mcp"));}
    @Test void dailyExportIsFrozenAndNeverAddsRowsTwice(){setup();assertEquals("SUCCESS",view(service.export(DAY,false)).path("status").asText());assertEquals(1,mapper.export(DAY).confirmedRows);service.export(DAY,false);verify(session,times(1)).call(eq("smartsheet_add_records"),anyMap());var snapshot=json.valueToTree(service.preview(DAY));assertTrue(snapshot.toString().contains("000731"));assertFalse(snapshot.toString().contains("user_id"));assertEquals(1,db.queryForObject("SELECT count(*) FROM wecom_document_export",Integer.class));}
    @Test void uncertainWriteIsTerminalAndPreservesSnapshot(){setup();when(session.call(eq("smartsheet_add_records"),anyMap())).thenThrow(new DocumentMcpClient.Failure("MCP_TRANSPORT_FAILED",true));assertEquals("UNKNOWN",view(service.export(DAY,false)).path("status").asText());assertEquals(0,mapper.export(DAY).confirmedRows);service.export(DAY,false);verify(session,times(1)).call(eq("smartsheet_add_records"),anyMap());}
    @Test void setupFailureNeverCreatesAnotherDocument(){when(session.call(eq("create_doc"),anyMap())).thenThrow(new DocumentMcpClient.Failure("MCP_TRANSPORT_FAILED",true));assertEquals("UNKNOWN",view(service.setup(false)).path("status").asText());service.setup(false);verify(session,times(1)).call(eq("create_doc"),anyMap());}
    @Test void missingReportAndFutureDayDoNotCallMcp(){setup();clearInvocations(client,session);db.update("DELETE FROM daily_group_report");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.export(DAY,false));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.export(DAY.plusDays(1),true));verifyNoInteractions(client,session);}
    @Test void interruptedSendingIsNotBlindlyReplayed(){setup();mapper.freeze(DAY,"test-doc","sheet1","[]",0);mapper.claim(DAY);clearInvocations(client,session);assertEquals("SENDING",view(service.export(DAY,false)).path("status").asText());verifyNoInteractions(client,session);}
    @Test void concurrentExportsHaveOneRemoteWrite()throws Exception{setup();try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->service.export(DAY,false));var b=pool.submit(()->service.export(DAY,false));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}assertEquals("SUCCESS",mapper.export(DAY).status);verify(session,times(1)).call(eq("smartsheet_add_records"),anyMap());}
    @Test void confirmedFirstBatchIsKeptWhenSecondBatchTimesOut(){
        for(int i=1;i<=100;i++){String code=String.format("%06d",800000+i);db.update("INSERT INTO stock_info(symbol,name,exchange) VALUES(?,'fixture','SH')",code);db.update("INSERT INTO stock_watchlist(symbol) VALUES(?)",code);}
        setup();var count=new AtomicInteger();when(session.call(eq("smartsheet_add_records"),anyMap())).thenAnswer(invocation->{if(count.incrementAndGet()>1)throw new DocumentMcpClient.Failure("MCP_TRANSPORT_FAILED",true);var result=json.createObjectNode();var ids=result.putArray("records");for(int i=0;i<100;i++)ids.addObject().put("record_id","ack"+i);return result;});
        assertEquals("UNKNOWN",view(service.export(DAY,false)).path("status").asText());assertEquals(100,mapper.export(DAY).confirmedRows);assertEquals(101,mapper.export(DAY).totalRows);service.export(DAY,false);assertEquals(2,count.get());
    }
    @Test void publicSignalAdviceAndLeadingZeroCodeAreExportedButPersonalDataIsNot(){
        db.update("INSERT INTO personal_position(group_hash,user_id,symbol,quantity,cost_price,sellable_quantity,reported_on) VALUES(repeat('b',64),'private-holder','000731',888,123.4567,100,?)",DAY);
        Long id=db.queryForObject("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason,risk_note,signal_at) VALUES('000731',?,'INTRADAY_V1','test','RAPID_RISE',7.9,'本地涨幅条件','延迟风险',TIMESTAMPTZ '2026-10-22 11:10:00+08') RETURNING id",Long.class,DAY);
        db.update("INSERT INTO signal_advice(signal_id,provider,model,prompt_version,status,input_snapshot,advice) VALUES(?,'test','test','test','SUCCESS','{}','{\"suggestion\":\"等待回踩\",\"risks\":[\"回落风险\"],\"invalidation\":\"跌回触发位\"}')",id);
        String preview=view(service.preview(DAY)).toString();assertTrue(preview.contains("000731"));assertTrue(preview.contains("等待回踩"));assertTrue(preview.contains("回落风险"));assertTrue(preview.contains("11:10"));assertFalse(preview.contains("03:10"));assertFalse(preview.contains("private-holder"));assertFalse(preview.contains("123.4567"));
        assertEquals(1,view(service.preview(DAY)).path("records").size());verifyNoInteractions(client);
    }
    @Test void changedColumnSchemaStopsBeforeAnyRecordWrite(){setup();when(session.call(eq("smartsheet_get_fields"),anyMap())).thenReturn(fields(false));assertEquals("FAILED",view(service.export(DAY,false)).path("status").asText());verify(session,never()).call(eq("smartsheet_add_records"),anyMap());assertEquals("MCP_FIELD_SCHEMA_CHANGED",mapper.export(DAY).lastError);}
    @Test void authorizationExpiryIsRecordedWithoutAppending(){setup();when(client.open()).thenThrow(new DocumentMcpClient.Failure("MCP_AUTH_REQUIRED",false));assertEquals("FAILED",view(service.export(DAY,false)).path("status").asText());assertEquals("MCP_AUTH_REQUIRED",mapper.export(DAY).lastError);verify(session,never()).call(eq("smartsheet_add_records"),anyMap());}
    @Test void incompleteRecordAcknowledgementIsUncertain(){setup();when(session.call(eq("smartsheet_add_records"),anyMap())).thenReturn(json.createObjectNode().put("errcode",0));assertEquals("UNKNOWN",view(service.export(DAY,false)).path("status").asText());service.export(DAY,false);verify(session,times(1)).call(eq("smartsheet_add_records"),anyMap());}
    @Test void httpDefaultsAreDryAndStatusDoesNotExposeCredentials()throws Exception {
        String setup=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/documents/wecom/setup")).andReturn().getResponse().getContentAsString();assertTrue(json.readTree(setup).path("dryRun").asBoolean());
        var preview=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/documents/wecom/exports").contentType("application/json").content("{\"tradeDate\":\"2026-10-22\"}")).andReturn().getResponse();assertEquals(200,preview.getStatus());assertTrue(json.readTree(preview.getContentAsString()).path("dryRun").asBoolean());
        String status=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/documents/wecom")).andReturn().getResponse().getContentAsString();assertFalse(status.contains("test-mcp"));assertFalse(status.contains("mcp-url"));verifyNoInteractions(client);
    }
    @Test void renewedAuthorizationCanExplicitlyRetryOnlyKnownFailure(){
        setup();when(client.open()).thenThrow(new DocumentMcpClient.Failure("MCP_AUTH_REQUIRED",false));assertEquals("FAILED",view(service.export(DAY,false)).path("status").asText());
        doReturn(session).when(client).open();assertTrue(view(service.retry(DAY,true)).path("canRetry").asBoolean());assertEquals("SUCCESS",view(service.retry(DAY,false)).path("status").asText());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.retry(DAY,false));verify(session,times(1)).call(eq("smartsheet_add_records"),anyMap());
    }
    @Test void retryCannotResendAnUnknownWrite(){setup();when(session.call(eq("smartsheet_add_records"),anyMap())).thenThrow(new DocumentMcpClient.Failure("MCP_TRANSPORT_FAILED",true));service.export(DAY,false);assertFalse(view(service.retry(DAY,true)).path("canRetry").asBoolean());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.retry(DAY,false));verify(session,times(1)).call(eq("smartsheet_add_records"),anyMap());}
    @Test void latestSavedDailySignalIsNotHiddenByOlderIntradayTimestamp(){
        db.update("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason,signal_at) VALUES('000731',?,'INTRADAY_V1','test','RAPID_RISE',7.9,'较早盘中信号',TIMESTAMPTZ '2026-10-22 11:10:00+08')",DAY);
        db.update("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason) VALUES('000731',?,'SWING_TREND_V1','test','SELL_WATCH',7.8,'最新日线信号')",DAY);
        String preview=view(service.preview(DAY)).toString();assertTrue(preview.contains("最新日线信号"));assertFalse(preview.contains("较早盘中信号"));assertTrue(preview.contains("2026-10-22 收盘"));
    }
}
