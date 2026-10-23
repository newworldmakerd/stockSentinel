package com.stocksentinel.notification;

import com.stocksentinel.signal.SignalMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    @org.springframework.beans.factory.annotation.Value("${llm.enabled:false}") private boolean waitForAdvice;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.personal.PositionService positions;
    private final NotificationMapper mapper;
    private final NotificationSettings settings;
    private final SignalMapper signals;
    private final NotificationTemplate template;
    public NotificationService(NotificationMapper mapper,NotificationSettings settings,SignalMapper signals,NotificationTemplate template) {
        this.mapper=mapper;this.settings=settings;this.signals=signals;this.template=template;
    }
    /** Called from BEFORE_COMMIT: notification and signal commit or roll back together. */
    @Transactional(propagation=Propagation.MANDATORY)
    public NotificationRow enqueue(long id) {
        if (!settings.enabled) return null;
        if (signals.find(id)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SIGNAL_NOT_FOUND");
        NotificationRow first=null;
        for(String chat:settings.chatIds){
            String key="smart-bot:"+id+":"+settings.destinationHash(chat);
            mapper.enqueue(id,key,chat);
            if(first==null)first=mapper.byKey(key);
        }
        if(positions!=null)positions.enqueue(id);
        return first;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public NotificationRow enqueueManual(long id) { return enqueue(id); }
    private String destinationHash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((settings.botId+"\n"+settings.chatId).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException("Notification key unavailable"); }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public NotificationRow claim() {
        mapper.expire();
        NotificationRow row=null;
        for(String chat:settings.pollTargets("signals")){
            String hash=settings.destinationHash(chat);row=mapper.claim(UUID.randomUUID().toString(),hash,waitForAdvice);if(row!=null)break;
        }
        if (row!=null && row.payload==null) {
            try {
                var signal=signals.find(row.signalId);
                if (signal==null) throw new IllegalStateException("Missing notification signal");
                var advice=mapper.advice(row.signalId);
                row.payload=template.render(signal,advice,signals.stockName(row.signalId));
                if(waitForAdvice && advice==null)row.payload=row.payload.replace("大模型建议：暂未生成或未启用。",
                    "FAILED".equals(mapper.adviceStatus(row.signalId))?"大模型建议：本次分析失败，先发送规则提醒。":"大模型建议：等待分析超时，先发送规则提醒。");
                mapper.payload(row.id,row.claimToken,row.payload);
            } catch (Exception invalid) {
                mapper.finish(row.id,row.claimToken,"FAILED","INVALID_NOTIFICATION_INPUT",0);
                return null;
            }
        }
        return row;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(NotificationRow row,NotificationClient.Delivery delivery) {
        String status=switch(delivery.outcome()) {
            case SUCCESS -> "SUCCESS"; case UNKNOWN -> "UNKNOWN"; case REJECTED -> "FAILED";
            case RETRY -> row.attempts>=3 ? "FAILED" : "RETRY";
        };
        // Only client-owned codes are stored; never provider errmsg / raw exceptions.
        String code=switch(delivery.outcome()) {
            case SUCCESS -> null; case UNKNOWN -> "DELIVERY_UNCONFIRMED";
            case REJECTED -> "PROVIDER_REJECTED"; case RETRY -> "PROVIDER_UNAVAILABLE";
        };
        mapper.finish(row.id,row.claimToken,status,code,30*(1 << Math.min(row.attempts-1,2)));
    }
}
