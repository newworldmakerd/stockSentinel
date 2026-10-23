package com.stocksentinel.report;

import com.stocksentinel.notification.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Explicit operator resend has independent receipts; never resets a historical SUCCESS/UNKNOWN. */
@Service
public class ReportResendService {
    private final ReportMapper reports;private final NotificationMapper records;private final DailyReportService daily;
    private final NotificationSettings settings;private final TradingDatePolicy dates;private final ObjectProvider<NotificationClient> clients;
    private final boolean enabled,worker;
    public ReportResendService(ReportMapper reports,NotificationMapper records,DailyReportService daily,NotificationSettings settings,TradingDatePolicy dates,ObjectProvider<NotificationClient> clients,
            @Value("${reports.enabled:false}") boolean enabled,@Value("${reports.worker-enabled:true}") boolean worker){this.reports=reports;this.records=records;this.daily=daily;this.settings=settings;this.dates=dates;this.clients=clients;this.enabled=enabled;this.worker=worker;}
    private UUID request(String id){try{return UUID.fromString(id);}catch(Exception bad){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"RESEND_REQUEST_UUID_REQUIRED");}}
    private String key(UUID request,String chat){return "report-resend:"+dates.today()+":"+request+":"+settings.destinationHash(chat);}
    @Transactional public Object enqueue(String requestId){
        var request=request(requestId);
        if(!enabled||!settings.enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DAILY_REPORT_DISABLED");
        if(!dates.afterClose()||!"OPEN".equals(dates.calendarStatus(dates.today())))throw new ResponseStatusException(HttpStatus.CONFLICT,"CURRENT_CLOSED_TRADING_DAY_REQUIRED");
        String payload=null;for(String chat:settings.chatIds)if(records.byKey(key(request,chat))==null){if(payload==null)payload=daily.render(dates.today());reports.enqueueResend(key(request,chat),chat,payload);}
        return status(requestId);
    }
    public Object status(String requestId){var request=request(requestId);var outcomes=new ArrayList<Map<String,Object>>();int index=0;
        for(String chat:settings.chatIds){var row=records.byKey(key(request,chat));outcomes.add(row==null?Map.of("groupIndex",++index,"status","NOT_CREATED"):Map.of("groupIndex",++index,"id",row.id,"status",row.status,"attempts",row.attempts));}
        return Map.of("requestId",request.toString(),"tradeDate",dates.today(),"groups",outcomes);
    }
    @Scheduled(fixedDelayString="${reports.poll-ms:5000}",scheduler="reportTaskScheduler") public void tick(){if(worker)dispatchOne();}
    public boolean dispatchOne(){if(!enabled||!settings.enabled)return false;var client=clients.getIfAvailable();if(client==null)return false;records.expire();
        NotificationRow row=null;for(String chat:settings.pollTargets("report-resend")){row=reports.claimResend(chat,UUID.randomUUID().toString());if(row!=null)break;}if(row==null)return false;
        NotificationClient.Delivery result;try{result=client.send(row.targetChatId,row.payload);}catch(Exception failed){result=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED");}
        String status=switch(result.outcome()){case SUCCESS->"SUCCESS";case UNKNOWN->"UNKNOWN";case REJECTED->"FAILED";case RETRY->row.attempts>=3?"FAILED":"RETRY";};
        records.finish(row.id,row.claimToken,status,"SUCCESS".equals(status)?null:"DELIVERY_"+result.outcome(),60);return true;
    }
}
