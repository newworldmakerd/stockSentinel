package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.notification.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback WebSocket handshake and masked frames, never a vendor connection. */
class SmartBotProtocolTest {
    @Test void moreThanTenTransportFailuresRecoverAfterCooldownWithoutRestart() throws Exception {
        try(var bot=new Bot("SUCCESS")){
            var attempts=new AtomicInteger();var nanos=new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
            var client=new SmartBotClient(new ObjectMapper(),"test-bot","test-only-credential",listener->{
                if(attempts.incrementAndGet()<=11)return CompletableFuture.failedFuture(new ConnectException("local simulated outage"));
                return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+bot.server.getLocalPort()),listener);
            },700,false,id->{},nanos::get,()->1.0,300);
            try{
                for(int i=0;i<11;i++){
                    assertEquals(NotificationClient.Outcome.RETRY,client.send("test-group","not yet sent").outcome());
                    assertEquals(i+1,attempts.get());
                    assertEquals(NotificationClient.Outcome.RETRY,client.send("test-group","still cooling").outcome());
                    assertEquals(i+1,attempts.get());
                    if(i>=9){assertEquals("RECONNECT_COOLDOWN",client.discoveryStatus());assertEquals(300L,client.connectionDiagnostics().get("nextProbeSeconds"));}
                    nanos.addAndGet(TimeUnit.MINUTES.toNanos(6));
                }
                assertEquals(NotificationClient.Outcome.SUCCESS,client.send("test-group","recovered").outcome());
                assertEquals("AUTHENTICATED",client.discoveryStatus());assertEquals(12,attempts.get());
                assertEquals(1,bot.auths.get());assertEquals(1,bot.sends.get());
                assertFalse(client.connectionDiagnostics().toString().contains("test-only-credential"));
            }finally{client.destroy();}
        }
    }
    @Test void authenticatedGroupTextReachesCommandHookAndAckTransportRemainsUsable() throws Exception {
        try(var bot=new Bot("SUCCESS")) {
            var client=bot.client();var received=new CompletableFuture<com.stocksentinel.commands.GroupMessage>();
            client.onGroupMessage(received::complete);
            try {
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(client,"maintain");
                Bot.send(bot.socket.getOutputStream(),"{\"cmd\":\"aibot_msg_callback\",\"body\":{\"msgid\":\"command-one\",\"aibotid\":\"test-bot\",\"chattype\":\"group\",\"chatid\":\"test-group\",\"from\":{\"userid\":\"test-user\"},\"msgtype\":\"text\",\"text\":{\"content\":\"监控600487\"}}}",true);
                assertEquals("监控600487",received.get(2,TimeUnit.SECONDS).text);
                assertEquals(NotificationClient.Outcome.SUCCESS,client.send("test-group","已加入").outcome());
            } finally {client.destroy();}
        }
    }
    private final ObjectMapper json=new ObjectMapper();
    static class Bot implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger sends=new AtomicInteger(),auths=new AtomicInteger();
        final BlockingQueue<String> commands=new LinkedBlockingQueue<>();
        final List<String> targets=new CopyOnWriteArrayList<>();
        volatile Socket socket;
        final Thread thread;
        volatile Throwable failure;
        final String scenario;
        Bot(String scenario) throws Exception {
            this.scenario=scenario;
            server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            thread=Thread.ofPlatform().daemon().start(()->{
                try {
                    socket=server.accept();socket.setSoTimeout(3000);
                    var in=socket.getInputStream();var out=socket.getOutputStream();
                    StringBuilder headers=new StringBuilder();
                    while(!headers.toString().endsWith("\r\n\r\n")) { int c=in.read();if(c<0) return;headers.append((char)c); }
                    String key=headers.toString().lines().filter(l->l.toLowerCase().startsWith("sec-websocket-key:"))
                        .findFirst().orElseThrow().split(":",2)[1].trim();
                    String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
                    var mapper=new ObjectMapper();
                    while(!socket.isClosed()) {
                        int opcode=in.read();if(opcode<0) return;
                        int length=in.read();if(length<0) return;
                        boolean masked=(length&128)!=0;length&=127;
                        if(length==126) length=(in.read()<<8)|in.read();
                        if(length==127) throw new IOException("Unexpected oversized test frame");
                        byte[] mask=masked ? in.readNBytes(4) : null;
                        byte[] data=in.readNBytes(length);if(data.length!=length) return;
                        if(masked) for(int i=0;i<data.length;i++) data[i]^=mask[i%4];
                        if((opcode&15)==8) return;
                        if((opcode&15)!=1) continue;
                        var frame=mapper.readTree(data);String cmd=frame.path("cmd").asText();commands.add(cmd);
                        String id=frame.at("/headers/req_id").asText();
                        assertFalse(id.isBlank());
                        if(cmd.equals("aibot_subscribe")) {
                            auths.incrementAndGet();
                            assertEquals("test-bot",frame.at("/body/bot_id").asText());
                            assertEquals("test-only-credential",frame.at("/body/secret").asText());
                        }
                        if(cmd.equals("aibot_send_msg")) {
                            sends.incrementAndGet();targets.add(frame.at("/body/chatid").asText());
                            assertEquals("markdown",frame.at("/body/msgtype").asText());
                            if(scenario.equals("NO_ACK")) continue;
                        }
                        int code=cmd.equals("aibot_subscribe") && scenario.equals("AUTH_FAIL") ? 40001 :
                            cmd.equals("aibot_send_msg") && scenario.equals("BUSY") ? -1 :
                            cmd.equals("aibot_send_msg") && scenario.equals("REJECT") ? 40058 : 0;
                        String response=mapper.writeValueAsString(Map.of("headers",Map.of("req_id",id),"errcode",code,"errmsg","not logged"));
                        send(out,response,scenario.equals("FRAGMENTS"));
                        if(cmd.equals("aibot_send_msg") && scenario.equals("TAKEOVER"))
                            send(out,"{\"cmd\":\"aibot_event_callback\",\"body\":{\"event\":{\"eventtype\":\"disconnected_event\"}}}",false);
                    }
                } catch(SocketException|EOFException expectedClose) { }
                catch(Throwable problem) { failure=problem; }
            });
        }
        static void send(OutputStream out,String text,boolean fragments) throws Exception {
            byte[] data=text.getBytes(StandardCharsets.UTF_8);
            if(fragments) {
                int middle=data.length/2;part(out,Arrays.copyOfRange(data,0,middle),1);
                part(out,Arrays.copyOfRange(data,middle,data.length),128);
            } else part(out,data,129);
            out.flush();
        }
        static void part(OutputStream out,byte[] data,int opcode) throws Exception {
            out.write(opcode);
            if(data.length<126) out.write(data.length);
            else {out.write(126);out.write(data.length>>8);out.write(data.length&255);}
            out.write(data);
        }
        SmartBotClient client() {
            return new SmartBotClient(new ObjectMapper(),"test-bot","test-only-credential",
                listener->HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:"+server.getLocalPort()),listener),700);
        }
        @Override public void close() throws Exception {
            if(socket!=null) socket.close();server.close();thread.join(1500);
            if(failure!=null) throw new AssertionError("Local protocol server failed",failure);
        }
    }
    @Test void persistentAuthenticatedConnectionAndFragmentedAcks() throws Exception {
        try(var bot=new Bot("FRAGMENTS")) {
            var client=bot.client();try {
                assertEquals(NotificationClient.Outcome.SUCCESS,client.send("test-chat","本地规则提醒").outcome());
                assertEquals(NotificationClient.Outcome.SUCCESS,client.send("test-chat","第二条").outcome());
                assertEquals(1,bot.auths.get());assertEquals(2,bot.sends.get());
                assertEquals(List.of("test-chat","test-chat"),bot.targets);
            } finally {client.destroy();}
        }
    }
    @Test void authFailureSendsNoNotificationAndDoesNotReconnect() throws Exception {
        try(var bot=new Bot("AUTH_FAIL")) {var client=bot.client();try {
            assertEquals(NotificationClient.Outcome.REJECTED,client.send("test-chat","content").outcome());
            assertEquals(NotificationClient.Outcome.REJECTED,client.send("test-chat","content").outcome());
            assertEquals(1,bot.auths.get());assertEquals(0,bot.sends.get());
        }finally {client.destroy();}}
    }
    @Test void missingAckIsUnknownAndNotSuccess() throws Exception {
        try(var bot=new Bot("NO_ACK")) {var client=bot.client();try {
            assertEquals(NotificationClient.Outcome.UNKNOWN,client.send("test-chat","content").outcome());
            assertEquals(1,bot.sends.get());
        }finally {client.destroy();}}
    }
    @Test void temporaryAndPermanentBusinessErrorsDiffer() throws Exception {
        for(var scenario:List.of("BUSY","REJECT")) try(var bot=new Bot(scenario)) {var client=bot.client();try {
            assertEquals(scenario.equals("BUSY") ? NotificationClient.Outcome.RETRY : NotificationClient.Outcome.REJECTED,
                client.send("test-chat","content").outcome());
        }finally {client.destroy();}}
    }
    @Test void takeoverStopsFurtherSends() throws Exception {
        try(var bot=new Bot("TAKEOVER")) {var client=bot.client();try {
            client.send("test-chat","content");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(bot.commands.size()<2 && System.nanoTime()<until) Thread.onSpinWait();
            // Drain the takeover frame before testing a subsequent request.
            Thread.sleep(100);
            assertEquals(NotificationClient.Outcome.REJECTED,client.send("test-chat","content").outcome());
            assertEquals(1,bot.sends.get());
        }finally {client.destroy();}}
    }
    @Test void unreachableGatewayIsRetryableBeforeSend() throws Exception {
        var client=new SmartBotClient(json,"test-bot","test-only-credential",listener->
            CompletableFuture.failedFuture(new ConnectException("local stand-in unavailable")),100);
        try {assertEquals(NotificationClient.Outcome.RETRY,client.send("test-chat","content").outcome());}
        finally {client.destroy();}
    }
    @Test void heartbeatUsesJsonPingAndWaitsForAck() throws Exception {
        try(var bot=new Bot("SUCCESS")) {var client=bot.client();try {
            assertEquals(NotificationClient.Outcome.SUCCESS,client.send("test-chat","content").outcome());
            org.springframework.test.util.ReflectionTestUtils.setField(client,"lastHeartbeat",0L);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(client,"maintain");
            assertTrue(bot.commands.contains("ping"));
            assertEquals(NotificationClient.Outcome.SUCCESS,client.send("test-chat","second").outcome());
            assertEquals(1,bot.auths.get());
        }finally {client.destroy();}}
    }
    @Test void reconnectsAfterKnownPreSendFailureWithoutReplayingAnyMessage() throws Exception {
        try(var bot=new Bot("SUCCESS")) {
            var attempts=new AtomicInteger();
            var client=new SmartBotClient(json,"test-bot","test-only-credential",listener->{
                if(attempts.incrementAndGet()==1) return CompletableFuture.failedFuture(new ConnectException("stand-in down"));
                return HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:"+bot.server.getLocalPort()),listener);
            },700);
            try {
                assertEquals(NotificationClient.Outcome.RETRY,client.send("test-chat","content").outcome());
                org.springframework.test.util.ReflectionTestUtils.setField(client,"nextConnect",0L);
                assertEquals(NotificationClient.Outcome.SUCCESS,client.send("test-chat","content").outcome());
                assertEquals(2,attempts.get());assertEquals(1,bot.sends.get());
            }finally {client.destroy();}
        }
    }
    private SmartBotClient discovery(Bot bot,java.util.function.Consumer<String> callback) {
        return new SmartBotClient(json,"test-bot","test-only-credential",listener->HttpClient.newHttpClient()
            .newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+bot.server.getLocalPort()),listener),700,true,callback);
    }
    @Test void discoveryReceivesGroupIdWithoutSendingOrReplying() throws Exception {
        try(var bot=new Bot("SUCCESS")) {
            var found=new CompletableFuture<String>();var client=discovery(bot,found::complete);
            try {
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(client,"maintain");
                assertEquals("AUTHENTICATED",client.discoveryStatus());
                Bot.send(bot.socket.getOutputStream(),"{\"cmd\":\"aibot_msg_callback\",\"body\":{\"aibotid\":\"test-bot\",\"chattype\":\"group\",\"chatid\":\"test-discovered-group\",\"text\":{\"content\":\"private text never printed\"}}}",true);
                assertEquals("test-discovered-group",found.get(2,TimeUnit.SECONDS));
                assertEquals(NotificationClient.Outcome.REJECTED,client.send("test-discovered-group","must not send").outcome());
                assertEquals(List.of("aibot_subscribe"),new ArrayList<>(bot.commands));assertEquals(0,bot.sends.get());
            }finally {client.destroy();}
        }
    }
    @Test void discoveryIgnoresSingleChatsWrongBotAndUnsafeOrMissingChatIds() throws Exception {
        try(var bot=new Bot("SUCCESS")) {
            var found=new CompletableFuture<String>();var count=new AtomicInteger();
            var client=discovery(bot,id->{count.incrementAndGet();found.complete(id);});
            try {
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(client,"maintain");
                for(String body:List.of(
                        "{\"aibotid\":\"test-bot\",\"chattype\":\"single\",\"chatid\":\"ignore-single\"}",
                        "{\"aibotid\":\"wrong-bot\",\"chattype\":\"group\",\"chatid\":\"ignore-bot\"}",
                        "{\"aibotid\":\"test-bot\",\"chattype\":\"group\",\"chatid\":\"bad\\nvalue\"}",
                        "{\"aibotid\":\"test-bot\",\"chattype\":\"group\",\"chatid\":123}",
                        "{\"aibotid\":\"test-bot\",\"chattype\":\"group\"}",
                        "{\"aibotid\":\"test-bot\",\"chattype\":\"group\",\"chatid\":\"valid-group\"}"))
                    Bot.send(bot.socket.getOutputStream(),"{\"cmd\":\"aibot_msg_callback\",\"body\":"+body+"}",false);
                assertEquals("valid-group",found.get(2,TimeUnit.SECONDS));assertEquals(1,count.get());assertEquals(0,bot.sends.get());
            }finally {client.destroy();}
        }
    }
    @Test void discoveryAuthRejectionIsVisibleWithoutLeakingProviderText() throws Exception {
        try(var bot=new Bot("AUTH_FAIL")) {
            var found=new CompletableFuture<String>();var client=discovery(bot,found::complete);
            try {
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(client,"maintain");
                assertEquals("AUTH_REJECTED",client.discoveryStatus());assertFalse(found.isDone());assertEquals(0,bot.sends.get());
            }finally {client.destroy();}
        }
    }
    @Test void discoveryMissingCredentialsExitsBeforeAnySpringOrDatabaseStartup() {
        var bytes=new ByteArrayOutputStream();
        assertEquals(2,ChatIdDiscovery.run(Map.of(),new PrintStream(bytes,true,StandardCharsets.UTF_8)));
        String output=bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("WECOM_BOT_ID"));assertFalse(output.contains("Spring"));assertFalse(output.contains("test-only-credential"));
    }
}
