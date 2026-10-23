package com.stocksentinel.personal;

import com.stocksentinel.notification.*;
import com.stocksentinel.report.DailyReportService;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class PersonalPriceDelivery {
    private final PersonalPriceMapper mapper;private final DailyReportService destination;private final NotificationSettings settings;private final ObjectProvider<NotificationClient> clients;private final boolean enabled,worker;
    public PersonalPriceDelivery(PersonalPriceMapper mapper,DailyReportService destination,NotificationSettings settings,ObjectProvider<NotificationClient> clients,@Value("${personal-price.enabled:false}") boolean enabled,@Value("${personal-price.worker-enabled:true}") boolean worker){this.mapper=mapper;this.destination=destination;this.settings=settings;this.clients=clients;this.enabled=enabled;this.worker=worker;}
    @Scheduled(fixedDelayString="${personal-price.poll-ms:2000}",scheduler="personalPriceTaskScheduler") public void tick(){if(worker)dispatchOne();}
    public boolean dispatchOne(){if(!enabled||!settings.enabled)return false;var client=clients.getIfAvailable();if(client==null)return false;mapper.expire();PersonalPriceMapper.Event row=null;String target=null;
        for(String chat:settings.pollTargets("personal")){row=mapper.claim(settings.destinationHash(chat),UUID.randomUUID().toString());if(row!=null){target=chat;break;}}if(row==null)return false;
        NotificationClient.Delivery result;try{result=client.send(target,row.payload);}catch(Exception failed){result=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED");}
        String status=switch(result.outcome()){case SUCCESS->"SUCCESS";case UNKNOWN->"UNKNOWN";case REJECTED->"FAILED";case RETRY->row.attempts>=3?"FAILED":"RETRY";};mapper.finish(row.id,row.claimToken,status,"SUCCESS".equals(status)?null:"DELIVERY_"+result.outcome());return true;
    }
}
