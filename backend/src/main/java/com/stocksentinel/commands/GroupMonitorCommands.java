package com.stocksentinel.commands;

import com.stocksentinel.notification.*;
import com.stocksentinel.stock.WatchlistMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded asynchronous command handling: callback text is ephemeral and never logged. */
@Service
public class GroupMonitorCommands implements DisposableBean {
    private final boolean enabled;
    private final NotificationSettings settings;
    private final ObjectProvider<MonitorIntentClient> intents;
    private final ObjectProvider<NotificationClient> notifications;
    private final WatchlistMapper stocks;
    private final CommandMapper commands;
    private final LoopbackMonitorApi api;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"wecom-group-commands");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public GroupMonitorCommands(@Value("${wecom.commands.enabled:false}") boolean enabled,NotificationSettings settings,
            ObjectProvider<MonitorIntentClient> intents,ObjectProvider<NotificationClient> notifications,
            WatchlistMapper stocks,CommandMapper commands,LoopbackMonitorApi api) {
        this.enabled=enabled;this.settings=settings;this.intents=intents;this.notifications=notifications;
        this.stocks=stocks;this.commands=commands;this.api=api;
    }
    public void accept(GroupMessage message) {
        if(!eligible(message)) return;
        try {worker.execute(()->handle(message));} catch(RejectedExecutionException full) { /* No unbounded queue or callback-thread model requests. */ }
    }
    private boolean eligible(GroupMessage m) {
        if(!enabled || m==null || !settings.botId.equals(m.botId) || !settings.chatId.equals(m.chatId)
                || m.userId==null || m.userId.isBlank() || settings.botId.equals(m.userId)
                || m.msgId==null || m.msgId.isBlank() || m.msgId.length()>256
                || m.msgId.codePoints().anyMatch(Character::isISOControl)
                || m.text==null || m.text.isBlank() || m.text.length()>1000 || m.text.contains("【StockSentinel】")) return false;
        if(m.createdAt!=null && (m.createdAt<Instant.now().getEpochSecond()-180 || m.createdAt>Instant.now().getEpochSecond()+30)) return false;
        String text=normalize(m.text);
        if(!(text.contains("监控") || text.contains("关注") || text.contains("盯盘"))) return false;
        for(String negative:new String[]{"取消","删除","不要","不用","停止","不再","别","不需要"}) if(text.contains(negative)) return false;
        return true;
    }
    /** Also validates direct invocations; useful for deterministic local tests. */
    public void handle(GroupMessage m) {
        if(!eligible(m)) return;
        String key;
        try {key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((m.botId+"\n"+m.chatId+"\n"+m.msgId).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible) {throw new IllegalStateException("Digest unavailable");}
        if(commands.claim(key)!=1) return;
        String reply;
        try {
            String query=intents.getObject().stockQuery(m.text);
            if(query==null) {commands.finish(key,"IGNORED",null);return;}
            query=normalize(query);
            if(query.length()<2 || query.length()>100 || !normalize(m.text).contains(query)
                    || !query.matches("[\\p{IsHan}A-Za-z0-9*]+") || (query.matches("[0-9]+") && !query.matches("[0-9]{6}"))) throw new IllegalArgumentException("Invalid stock query");
            var matches=stocks.search(query,20,0);
            final String resolvedQuery=query;
            var exact=matches.stream().filter(s->queryEquals(s.symbol,s.name,resolvedQuery)).toList();
            var selected=exact.size()==1 ? exact : matches;
            if(selected.size()!=1) {
                commands.finish(key,"UNRESOLVED",null);
                reply="未能唯一确认股票，请使用六位代码，例如：监控 600487。";
            } else {
                var stock=selected.get(0);
                var result=api.add(stock.symbol);
                commands.finish(key,result.status(),stock.symbol);
                reply=("ALREADY_MONITORED".equals(result.status()) ? "已在监控：" : "已加入关注并开启盘中监控：")
                    +stock.name+"（"+stock.symbol+"）。交易时段每分钟扫描，沿用已有监控参数。";
            }
        } catch(Exception rejected) {
            commands.finish(key,"FAILED",null);
            reply="本次添加未确认成功，请稍后重试或通过关注名单核实；未执行交易。";
        }
        try {
            var delivery=notifications.getObject().send(settings.chatId,"【StockSentinel】\n"+reply);
            commands.ack(key,delivery.outcome().name());
        } catch(Exception unavailable) {commands.ack(key,"UNKNOWN");}
    }
    private static boolean queryEquals(String symbol,String name,String query) {return symbol.equals(query) || normalize(name).equals(query);}
    private static String normalize(String text) {return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+","");}
    @Override public void destroy() {worker.shutdownNow();}
}
