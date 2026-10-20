package com.stocksentinel.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;

/** JDK21 persistent WebSocket; no Webhook, no raw wire logging. */
public class SmartBotClient implements NotificationClient,DisposableBean {
    private volatile java.util.function.Consumer<com.stocksentinel.commands.GroupMessage> groupMessages=m->{};
    public void onGroupMessage(java.util.function.Consumer<com.stocksentinel.commands.GroupMessage> handler) {groupMessages=java.util.Objects.requireNonNull(handler);}
    private static final URI GATEWAY=URI.create("wss://openws.work.weixin.qq.com");
    @FunctionalInterface public interface Connector {
        CompletableFuture<WebSocket> connect(WebSocket.Listener listener);
    }
    private final ObjectMapper json;
    private final String botId,secret;
    private final Connector connector;
    private final int timeoutMs;
    private final boolean receiveOnly;
    private final java.util.function.Consumer<String> discovered;
    private final ConcurrentMap<String,CompletableFuture<JsonNode>> pending=new ConcurrentHashMap<>();
    private final ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"wecom-smart-bot");t.setDaemon(true);return t;
    });
    private volatile WebSocket socket;
    private volatile boolean authenticated,stopped,takenOver,authRejected;
    private int reconnects;
    private long nextConnect,lastHeartbeat;

    public SmartBotClient(ObjectMapper json,NotificationSettings settings) {
        this(json,settings.botId,settings.secret,listener->HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5)).buildAsync(GATEWAY,listener),10000);
        maintenance.scheduleWithFixedDelay(this::maintain,0,1,TimeUnit.SECONDS);
    }
    /** Injectable transport is used only by local protocol tests. */
    public SmartBotClient(ObjectMapper json,String botId,String secret,Connector connector,int timeoutMs) {
        this(json,botId,secret,connector,timeoutMs,false,id->{});
    }
    public SmartBotClient(ObjectMapper json,String botId,String secret,Connector connector,int timeoutMs,
            boolean receiveOnly,java.util.function.Consumer<String> discovered) {
        this.json=json;this.botId=botId;this.secret=secret;this.connector=connector;this.timeoutMs=timeoutMs;
        this.receiveOnly=receiveOnly;this.discovered=discovered;
    }
    public static SmartBotClient discovery(ObjectMapper json,String botId,String secret,java.util.function.Consumer<String> discovered) {
        var client=new SmartBotClient(json,botId,secret,listener->HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5)).buildAsync(GATEWAY,listener),10000,true,discovered);
        client.maintenance.scheduleWithFixedDelay(client::maintain,0,1,TimeUnit.SECONDS);
        return client;
    }
    /** Safe state only; never returns credentials, callback text or connection exceptions. */
    public synchronized String discoveryStatus() {
        if(stopped) return "STOPPED";
        if(takenOver) return "TAKEN_OVER";
        if(authRejected) return "AUTH_REJECTED";
        if(authenticated) return "AUTHENTICATED";
        return reconnects>=10 ? "CONNECTION_FAILED" : "CONNECTING";
    }
    private synchronized void maintain() {
        if(stopped || takenOver || authRejected) return;
        try {
            if(!authenticated) { connect();return; }
            if(System.nanoTime()-lastHeartbeat>=TimeUnit.SECONDS.toNanos(30)) {
                JsonNode ack=request(socket,"ping",Map.of());
                if(!ok(ack)) throw new IllegalStateException("Heartbeat rejected");
                lastHeartbeat=System.nanoTime();
            }
        } catch(Exception unavailable) { disconnect(); }
    }
    private void connect() throws Exception {
        if(authenticated) return;
        if(stopped || takenOver || authRejected || reconnects>=10 || System.nanoTime()<nextConnect)
            throw new IllegalStateException("Connection unavailable");
        reconnects++;
        nextConnect=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.min(30,1L << Math.min(reconnects-1,5)));
        try {
            var future=connector.connect(new Frames());
            // Close a connection that arrives after the caller's bounded wait.
            java.util.concurrent.atomic.AtomicBoolean abandoned=new java.util.concurrent.atomic.AtomicBoolean();
            future.thenAccept(ws->{if(abandoned.get() || stopped || takenOver) ws.abort();});
            try { socket=future.get(timeoutMs,TimeUnit.MILLISECONDS); }
            catch(Exception timeout) { abandoned.set(true);future.thenAccept(WebSocket::abort);throw timeout; }
            JsonNode ack=request(socket,"aibot_subscribe",Map.of("bot_id",botId,"secret",secret));
            if(!ok(ack)) { authRejected=true;throw new IllegalStateException("Authentication rejected"); }
            if(takenOver || stopped) throw new IllegalStateException("Connection stopped");
            authenticated=true;reconnects=0;lastHeartbeat=System.nanoTime();
        } catch(Exception failure) { disconnect();throw failure; }
    }
    @Override public synchronized Delivery send(String chatId,String markdown) {
        if(receiveOnly) return new Delivery(Outcome.REJECTED,"DISCOVERY_RECEIVE_ONLY");
        if(chatId==null || chatId.isBlank() || markdown==null || markdown.isBlank()
                || markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4000)
            return new Delivery(Outcome.REJECTED,"INVALID_MESSAGE");
        try { connect(); }
        catch(Exception unavailable) {
            return new Delivery(authRejected || takenOver ? Outcome.REJECTED : Outcome.RETRY,"CONNECTION_UNAVAILABLE");
        }
        try {
            JsonNode ack=request(socket,"aibot_send_msg",Map.of("chatid",chatId,"msgtype","markdown",
                "markdown",Map.of("content",markdown)));
            if(ok(ack)) return new Delivery(Outcome.SUCCESS,"ACK_SUCCESS");
            if(!ack.path("errcode").isIntegralNumber()) return new Delivery(Outcome.UNKNOWN,"INVALID_ACK");
            int code=ack.path("errcode").asInt();
            // Only known temporary codes are automatically retried, not arbitrary provider errors.
            return new Delivery(code==-1 || code==45009 ? Outcome.RETRY : Outcome.REJECTED,"ACK_REJECTED");
        } catch(Exception unconfirmed) {
            // sendText might have reached the server even if its future failed.
            disconnect();return new Delivery(Outcome.UNKNOWN,"ACK_UNCONFIRMED");
        }
    }
    private JsonNode request(WebSocket ws,String cmd,Object body) throws Exception {
        String id=cmd+"_"+UUID.randomUUID();
        var ack=new CompletableFuture<JsonNode>();pending.put(id,ack);
        try {
            String frame=json.writeValueAsString(Map.of("cmd",cmd,"headers",Map.of("req_id",id),"body",body));
            ws.sendText(frame,true).get(timeoutMs,TimeUnit.MILLISECONDS);
            return ack.get(timeoutMs,TimeUnit.MILLISECONDS);
        } finally { pending.remove(id); }
    }
    private boolean ok(JsonNode ack) { return ack.path("errcode").isIntegralNumber() && ack.path("errcode").asInt()==0; }
    private void disconnect() {
        authenticated=false;
        var ws=socket;socket=null;if(ws!=null) ws.abort();
        pending.values().forEach(f->f.completeExceptionally(new IllegalStateException("Connection closed")));
    }
    private class Frames implements WebSocket.Listener {
        private final StringBuilder text=new StringBuilder();
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
            if(socket!=ws) { ws.request(1);return CompletableFuture.completedFuture(null); }
            if(text.length()+data.length()>65536) { ws.abort();return CompletableFuture.completedFuture(null); }
            text.append(data);
            if(last) {
                try {
                    JsonNode frame=json.readTree(text.toString());
                    if("aibot_event_callback".equals(frame.path("cmd").asText())
                            && "disconnected_event".equals(frame.at("/body/event/eventtype").asText())) {
                        takenOver=true;disconnect();
                    } else if(receiveOnly && authenticated && "aibot_msg_callback".equals(frame.path("cmd").asText())
                            && "group".equals(frame.at("/body/chattype").asText())
                            && botId.equals(frame.at("/body/aibotid").asText())) {
                        JsonNode id=frame.at("/body/chatid");
                        if(id.isTextual() && !id.asText().isBlank() && id.asText().length()<=256
                                && id.asText().codePoints().noneMatch(cp->Character.isISOControl(cp)
                                    || Character.isWhitespace(cp) || cp==0x2028 || cp==0x2029))
                            discovered.accept(id.asText());
                    } else if(!receiveOnly && authenticated && "aibot_msg_callback".equals(frame.path("cmd").asText())
                            && "group".equals(frame.at("/body/chattype").asText())
                            && botId.equals(frame.at("/body/aibotid").asText())
                            && "text".equals(frame.at("/body/msgtype").asText())) {
                        JsonNode body=frame.path("body");
                        Long created=body.path("create_time").isIntegralNumber() ? body.path("create_time").asLong() : null;
                        try {groupMessages.accept(new com.stocksentinel.commands.GroupMessage(body.path("msgid").asText(),
                            body.path("aibotid").asText(),body.path("chatid").asText(),body.at("/from/userid").asText(),
                            body.at("/text/content").asText(),created));} catch(RuntimeException rejected) { /* Keep ACK transport alive. */ }
                    } else {
                        var ack=pending.get(frame.at("/headers/req_id").asText());
                        if(ack!=null && frame.has("errcode")) ack.complete(frame);
                    }
                } catch(Exception invalidFrame) { ws.abort(); }
                finally { text.setLength(0); }
            }
            ws.request(1);return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason) {
            if(socket==ws) disconnect();return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws,Throwable error) { if(socket==ws) disconnect(); }
    }
    @Override public synchronized void destroy() {
        stopped=true;maintenance.shutdownNow();disconnect();
    }
}
