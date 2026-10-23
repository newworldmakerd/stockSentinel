package com.stocksentinel.report;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.notification.*;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class DailyReportService {
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.pullback.PullbackSettings pullback;
    private final ReportMapper mapper;private final WatchlistMapper watches;private final NotificationSettings settings;
    private final ObjectProvider<NotificationClient> clients;private final TradingDatePolicy dates;private final ObjectMapper json;
    private final boolean enabled;
    public DailyReportService(ReportMapper mapper,WatchlistMapper watches,NotificationSettings settings,ObjectProvider<NotificationClient> clients,
            TradingDatePolicy dates,ObjectMapper json,@Value("${reports.enabled:false}") boolean enabled) {
        this.mapper=mapper;this.watches=watches;this.settings=settings;this.clients=clients;this.dates=dates;this.json=json;this.enabled=enabled;
    }
    public String render(LocalDate date) {
        return new ReportNarrative(mapper,watches,json,pullback).render(date);
    }
    static String clean(String s,int max) {s=s.replaceAll("[\\r\\n\\p{Cntrl}<>`*]"," ");return s.codePointCount(0,s.length())<=max?s:s.substring(0,s.offsetByCodePoints(0,max))+"…";}
    public String destinationHash() {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((settings.botId+"\n"+settings.chatId).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Report key unavailable");}}
    public java.util.List<String> destinationHashes(){return settings.destinationHashes();}
    public ReportMapper.Delivery enqueue(LocalDate date) {
        if(!enabled || !settings.enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DAILY_REPORT_DISABLED");
        if(!date.equals(dates.today()) || !dates.afterClose() || !"OPEN".equals(dates.calendarStatus(date)))throw new ResponseStatusException(HttpStatus.CONFLICT,"CURRENT_CLOSED_TRADING_DAY_REQUIRED");
        String payload=null;
        for(String hash:destinationHashes())if(mapper.report(date,hash)==null){
            if(payload==null)payload=render(date);mapper.enqueue(date,hash,payload);
        }
        return mapper.report(date,destinationHash());
    }
    public boolean deliverOne() {
        if(!enabled || !settings.enabled)return false;var client=clients.getIfAvailable();if(client==null)return false;
        mapper.expire();ReportMapper.Delivery row=null;String target=null;
        for(String chat:settings.pollTargets("daily")){row=mapper.claim(settings.destinationHash(chat),UUID.randomUUID().toString());if(row!=null){target=chat;break;}}if(row==null)return false;
        NotificationClient.Delivery delivery;
        try {delivery=client.send(target,row.payload);}catch(Exception unconfirmed){delivery=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED");}
        String status=switch(delivery.outcome()){case SUCCESS->"SUCCESS";case UNKNOWN->"UNKNOWN";case REJECTED->"FAILED";case RETRY->row.attempts>=3?"FAILED":"RETRY";};
        mapper.delivered(row.id,row.claimToken,status,"SUCCESS".equals(status)?null:"DELIVERY_"+delivery.outcome().name());return true;
    }
}
