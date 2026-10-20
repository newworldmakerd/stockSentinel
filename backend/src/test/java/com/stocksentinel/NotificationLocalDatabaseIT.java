package com.stocksentinel;

import com.stocksentinel.notification.*;
import com.stocksentinel.signal.*;
import com.stocksentinel.common.entity.SignalEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real commits in own schema; smart bot is replaced before bean construction. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class NotificationLocalDatabaseIT {
    private static final String SCHEMA="stocksentinel_task05_"+UUID.randomUUID().toString().replace("-","");
    private static boolean created;
    private static synchronized void prepareSchema() {
        if (created) return;
        if (!SCHEMA.matches("stocksentinel_task05_[a-f0-9]{32}")) throw new IllegalStateException("Invalid isolated schema name");
        try (var connection=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),
                System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
             var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA \""+SCHEMA+"\"");
            created=true;
        } catch (Exception failed) { throw new IllegalStateException("Could not create isolated TASK-05 test schema",failed); }
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
        registry.add("wecom.enabled",()->true);
        registry.add("wecom.bot-id",()->"test-bot");
        registry.add("wecom.bot-secret",()->"test-only-credential");
        registry.add("wecom.chat-id",()->"test-group");
        registry.add("wecom.worker-enabled",()->false);
    }
    @AfterAll static void dropOwnSchema() throws Exception {
        if (!created) return;
        if (!SCHEMA.matches("stocksentinel_task05_[a-f0-9]{32}")) throw new IllegalStateException("Refusing unsafe schema cleanup");
        try (var connection=DriverManager.getConnection(System.getenv("STOCKSENTINEL_TEST_DB_URL"),
                System.getenv("STOCKSENTINEL_TEST_DB_USER"),System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
             var statement=connection.createStatement()) {
            statement.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
            created=false;
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notifications;
    @Autowired NotificationMapper mapper;
    @Autowired NotificationWorker worker;
    @Autowired SignalMapper signals;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired MockMvc http;
    @MockitoBean SmartBotClient client;
    private static final AtomicInteger CODES=new AtomicInteger(999700);
    private long signalId;
    @BeforeEach void setup() {
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
        // All tests share this owned schema only; retire queued rows of earlier cases.
        jdbc.update("UPDATE notify_record SET status='FAILED' WHERE status IN ('PENDING','RETRY','SENDING')");
        reset(client);
        String code=Integer.toString(CODES.getAndIncrement());
        jdbc.update("INSERT INTO stock_info(symbol,name,exchange) VALUES(?,?,'SH')",code,"notification fixture");
        var signal=new SignalEvent();signal.symbol=code;signal.tradeDate=LocalDate.of(2025,1,2);
        signal.strategyCode="SWING_TREND_V1";signal.strategyVersion="1.0.0";signal.signalType="BUY_WATCH";
        signal.referencePrice=BigDecimal.TEN;signal.reason="固定规则依据";signal.riskNote="固定样例风险";signal.details="{}";
        signals.insert(signal);signalId=signal.id;
    }
    private NotificationRow enqueue() {
        return new TransactionTemplate(transactions).execute(s->notifications.enqueue(signalId));
    }
    private void due(long id) { jdbc.update("UPDATE notify_record SET next_retry_at=now()-interval '1 second' WHERE id=?",id); }
    private void outcome(NotificationClient.Outcome result) {
        when(client.send(anyString(),anyString())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Socket delivery must not hold a database transaction");
            return new NotificationClient.Delivery(result,"test-only-code");
        });
    }
    @Test void signalEventQueuesWithinCommitAndSuccessfulAckPersists() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(s->events.publishEvent(new SignalCreated(signalId)));
        var row=jdbc.queryForObject("SELECT id FROM notify_record WHERE signal_id=?",Long.class,signalId);
        assertEquals("PENDING",mapper.find(row).status);
        outcome(NotificationClient.Outcome.SUCCESS);
        assertTrue(worker.dispatchOne());
        var sent=mapper.find(row);assertEquals("SUCCESS",sent.status);assertEquals(1,sent.attempts);
        assertNotNull(sent.sentAt);assertNull(sent.claimToken);assertNull(sent.nextRetryAt);
        assertTrue(sent.payload.contains("本地确定性规则"));
        verify(client).send(eq("test-group"),anyString());
        http.perform(get("/api/v1/notifications/"+row)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS")).andExpect(jsonPath("$.payload").doesNotExist())
            .andExpect(jsonPath("$.targetChatId").doesNotExist()).andExpect(jsonPath("$.claimToken").doesNotExist());
    }
    @Test void rollbackLeavesNoOutboxOrSend() {
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            notifications.enqueue(signalId);s.setRollbackOnly();
        });
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notify_record WHERE signal_id=?",Integer.class,signalId));
        assertFalse(worker.dispatchOne());verifyNoInteractions(client);
    }
    @Test void concurrentEnqueueAndDispatchAreDeduplicated() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(this::enqueue);var b=pool.submit(this::enqueue);
            long id=a.get(5,TimeUnit.SECONDS).id;assertEquals(id,b.get(5,TimeUnit.SECONDS).id);
            outcome(NotificationClient.Outcome.SUCCESS);
            var x=pool.submit(worker::dispatchOne);var y=pool.submit(worker::dispatchOne);
            assertNotEquals(x.get(5,TimeUnit.SECONDS),y.get(5,TimeUnit.SECONDS));
            assertEquals("SUCCESS",mapper.find(id).status);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notify_record WHERE signal_id=?",Integer.class,signalId));
            verify(client,times(1)).send(anyString(),anyString());
        }
    }
    @Test void retryHasExponentialBackoffBoundAndFrozenPayload() {
        var queued=enqueue();outcome(NotificationClient.Outcome.RETRY);
        assertTrue(worker.dispatchOne());var first=mapper.find(queued.id);
        assertEquals("RETRY",first.status);assertEquals(1,first.attempts);
        assertTrue(first.nextRetryAt.isAfter(java.time.OffsetDateTime.now().plusSeconds(20)));
        assertFalse(worker.dispatchOne());
        due(queued.id);assertTrue(worker.dispatchOne());var second=mapper.find(queued.id);
        assertTrue(second.nextRetryAt.isAfter(java.time.OffsetDateTime.now().plusSeconds(50)));
        assertEquals(first.payload,second.payload);
        due(queued.id);assertTrue(worker.dispatchOne());
        assertEquals("FAILED",mapper.find(queued.id).status);assertEquals(3,mapper.find(queued.id).attempts);
        assertFalse(worker.dispatchOne());verify(client,times(3)).send(eq("test-group"),eq(first.payload));
    }
    @Test void unknownAckNeverAutoRetries() {
        var row=enqueue();outcome(NotificationClient.Outcome.UNKNOWN);assertTrue(worker.dispatchOne());
        assertEquals("UNKNOWN",mapper.find(row.id).status);assertNull(mapper.find(row.id).nextRetryAt);
        assertEquals(row.id,enqueue().id);assertFalse(worker.dispatchOne());verify(client,times(1)).send(anyString(),anyString());
    }
    @Test void expiredLeaseIsUnknownAndLateOwnerCannotOverwrite() {
        var row=enqueue();var claimed=notifications.claim();assertEquals(row.id,claimed.id);
        jdbc.update("UPDATE notify_record SET lease_until=now()-interval '1 second' WHERE id=?",row.id);
        assertFalse(worker.dispatchOne());assertEquals("UNKNOWN",mapper.find(row.id).status);
        notifications.finish(claimed,new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"test"));
        assertEquals("UNKNOWN",mapper.find(row.id).status);verifyNoInteractions(client);
    }
    @Test void permanentRejectionIsFailedAndSanitized() {
        var row=enqueue();outcome(NotificationClient.Outcome.REJECTED);assertTrue(worker.dispatchOne());
        var failed=mapper.find(row.id);assertEquals("FAILED",failed.status);assertEquals("PROVIDER_REJECTED",failed.lastError);
        assertNull(failed.sentAt);assertFalse(worker.dispatchOne());
    }
    @Test void throwingClientIsUnknownNotBlindlyRetried() {
        var row=enqueue();when(client.send(anyString(),anyString())).thenThrow(new RuntimeException("do-not-store-provider-detail"));
        assertTrue(worker.dispatchOne());assertEquals("UNKNOWN",mapper.find(row.id).status);
        assertEquals("DELIVERY_UNCONFIRMED",mapper.find(row.id).lastError);
        assertFalse(worker.dispatchOne());
    }
    @Test void testApiDefaultsToDryRunThenExplicitlyQueuesSameSignalOnlyOnce() throws Exception {
        http.perform(post("/api/v1/notifications/test")).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notify_record WHERE signal_id=?",Integer.class,signalId));
        String body="{\"signalId\":"+signalId+",\"dryRun\":false}";
        for(int i=0;i<2;i++) http.perform(post("/api/v1/notifications/test").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM notify_record WHERE signal_id=?",Integer.class,signalId));
        http.perform(get("/api/v1/notifications?limit=201")).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }
    @Test void actualSocketAckCompletesPersistedNotification() throws Exception {
        var row=enqueue();
        try(var bot=new SmartBotProtocolTest.Bot("SUCCESS")) {
            var wire=bot.client();try {
                when(client.send(anyString(),anyString())).thenAnswer(call->{
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return wire.send(call.getArgument(0),call.getArgument(1));
                });
                assertTrue(worker.dispatchOne());assertEquals("SUCCESS",mapper.find(row.id).status);
                assertEquals(List.of("test-group"),bot.targets);assertEquals(1,bot.sends.get());
            }finally {wire.destroy();}
        }
    }
    @Test void actualSocketMissingAckPersistsUnknownWithoutReplay() throws Exception {
        var row=enqueue();
        try(var bot=new SmartBotProtocolTest.Bot("NO_ACK")) {
            var wire=bot.client();try {
                when(client.send(anyString(),anyString())).thenAnswer(call->wire.send(call.getArgument(0),call.getArgument(1)));
                assertTrue(worker.dispatchOne());assertEquals("UNKNOWN",mapper.find(row.id).status);
                assertFalse(worker.dispatchOne());assertEquals(1,bot.sends.get());
            }finally {wire.destroy();}
        }
    }
    @Test void changedDestinationCannotDeliverExistingQueueThroughAnotherBot() {
        var row=enqueue();
        var other=new NotificationService(mapper,new NotificationSettings(true,"SMART_BOT","different-test-bot","test-only-credential","test-group"),
            signals,new NotificationTemplate(new com.fasterxml.jackson.databind.ObjectMapper()));
        assertNull(new TransactionTemplate(transactions).execute(s->other.claim()));
        assertEquals("PENDING",mapper.find(row.id).status);verifyNoInteractions(client);
    }
}
