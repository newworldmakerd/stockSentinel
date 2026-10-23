package com.stocksentinel.personal;

import com.stocksentinel.notification.*;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

@Component
public class PositionDelivery {
    private final PositionMapper mapper;private final NotificationMapper notifications;private final NotificationSettings settings;
    private final ObjectProvider<NotificationClient> clients;private final boolean worker;
    public PositionDelivery(PositionMapper mapper,NotificationMapper notifications,NotificationSettings settings,ObjectProvider<NotificationClient> clients,@Value("${wecom.worker-enabled:true}") boolean worker){this.mapper=mapper;this.notifications=notifications;this.settings=settings;this.clients=clients;this.worker=worker;}
    @Scheduled(fixedDelayString="${personal-price.poll-ms:2000}",scheduler="personalPriceTaskScheduler") public void tick(){if(worker)dispatchOne();}
    public boolean dispatchOne(){if(!settings.enabled)return false;var client=clients.getIfAvailable();if(client==null)return false;notifications.expire();mapper.expire();
        NotificationRow row=null;for(String chat:settings.pollTargets("positions")){row=mapper.claim(chat,UUID.randomUUID().toString());if(row!=null)break;}if(row==null)return false;
        NotificationClient.Delivery result;try{result=client.send(row.targetChatId,row.payload);}catch(Exception failed){result=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED");}
        String status=switch(result.outcome()){case SUCCESS->"SUCCESS";case UNKNOWN->"UNKNOWN";case REJECTED->"FAILED";case RETRY->row.attempts>=3?"FAILED":"RETRY";};
        notifications.finish(row.id,row.claimToken,status,"SUCCESS".equals(status)?null:"DELIVERY_"+result.outcome(),30);return true;
    }
}
