package com.stocksentinel.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** No toString: credentials must never appear in logs or API responses. */
@Component
public class NotificationSettings {
    public final boolean enabled;
    public final String mode, botId, secret, chatId;
    public final java.util.List<String> chatIds;
    public NotificationSettings(boolean enabled,String mode,String botId,String secret,String chatId) {
        this(enabled,mode,botId,secret,chatId,"");
    }
    @org.springframework.beans.factory.annotation.Autowired
    public NotificationSettings(@Value("${wecom.enabled:false}") boolean enabled,
            @Value("${wecom.mode:SMART_BOT}") String mode,
            @Value("${wecom.bot-id:}") String botId, @Value("${wecom.bot-secret:}") String secret,
            @Value("${wecom.chat-id:}") String chatId, @Value("${wecom.chat-ids:}") String chatIds) {
        this.enabled=enabled; this.mode=mode; this.botId=botId; this.secret=secret;
        var targets=new java.util.LinkedHashSet<String>();
        if(chatId!=null&&!chatId.isBlank())targets.add(chatId.trim());
        if(chatIds!=null)for(String target:chatIds.split(",",-1))if(!target.isBlank())targets.add(target.trim());
        if(targets.size()>20 || targets.stream().anyMatch(t->t.length()>256 || t.codePoints().anyMatch(c->Character.isISOControl(c)||Character.isWhitespace(c))))
            throw new IllegalArgumentException("Invalid target chat configuration");
        this.chatIds=java.util.List.copyOf(targets);this.chatId=this.chatIds.isEmpty()?"":this.chatIds.getFirst();
        if (enabled && (!"SMART_BOT".equals(mode) || botId.isBlank() || secret.isBlank()
                || this.chatId.isBlank() || this.chatId.length()>256))
            throw new IllegalArgumentException("Enabled smart bot requires credentials and target chat configuration");
    }
    private final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> cursors=new java.util.concurrent.ConcurrentHashMap<>();
    public java.util.List<String> pollTargets(String worker) {
        if(chatIds.size()<2)return chatIds;
        int start=Math.floorMod(cursors.computeIfAbsent(worker,k->new java.util.concurrent.atomic.AtomicInteger()).getAndIncrement(),chatIds.size());
        var ordered=new java.util.ArrayList<String>();for(int i=0;i<chatIds.size();i++)ordered.add(chatIds.get((start+i)%chatIds.size()));return ordered;
    }
    public boolean accepts(String chat){return chat!=null&&chatIds.contains(chat);}
    public String destinationHash(String chat) {
        if(!accepts(chat))throw new IllegalArgumentException("Unconfigured destination");
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest((botId+"\n"+chat).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("Destination hash unavailable");}
    }
    public java.util.List<String> destinationHashes(){return chatIds.stream().map(this::destinationHash).toList();}
}
