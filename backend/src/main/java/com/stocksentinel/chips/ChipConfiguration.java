package com.stocksentinel.chips;
import com.fasterxml.jackson.databind.ObjectMapper;import com.stocksentinel.jobs.TradingDatePolicy;import com.stocksentinel.notification.*;
import java.time.*;import java.util.*;import javax.sql.DataSource;
import org.springframework.context.annotation.*;import org.springframework.beans.factory.*;import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)@ConditionalOnProperty(name="chips.enabled",havingValue="true")
public class ChipConfiguration {
    @Bean ChipClient chipClient(@Value("${market.python-url:http://127.0.0.1:8000}")String url){return new ChipClient(url);}
    @Bean ChipWriter chipWriter(ChipMapper mapper,NotificationSettings bot){return new ChipWriter(mapper,bot);}
    @Bean ChipService chipService(ChipMapper mapper,ChipClient client,ChipSettings settings,TradingDatePolicy dates,ObjectMapper json,Clock clock,DataSource source,ChipWriter writer,@Value("${stocksentinel.lock-namespace:76301}")int namespace){return new ChipService(mapper,client,settings,dates,json,clock,source,writer,namespace);}
    @Bean(name="chipScheduler")ThreadPoolTaskScheduler chipScheduler(){var s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setThreadNamePrefix("chip-scan-");return s;}
    @Bean Tasks chipTasks(ChipService service,ChipMapper mapper,ChipSettings settings,TradingDatePolicy dates,NotificationSettings bot,ObjectProvider<NotificationClient> clients,Clock clock){return new Tasks(service,mapper,settings,dates,bot,clients,clock);}
    public record Tasks(ChipService service,ChipMapper mapper,ChipSettings settings,TradingDatePolicy dates,NotificationSettings bot,ObjectProvider<NotificationClient> clients,Clock clock){
        @Scheduled(fixedDelayString="${chips.scan-delay-ms:30000}",scheduler="chipScheduler")public void scan(){var time=LocalTime.now(clock.withZone(TradingDatePolicy.ZONE));if(!settings.schedule||time.isBefore(LocalTime.of(15,30))||time.isAfter(LocalTime.of(23,0))||!"OPEN".equals(dates.calendarStatus(dates.today())))return;try{service.scan(dates.today(),false);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(Tasks.class).warn("chip_scan_failed");}}
        @Scheduled(fixedDelayString="${chips.delivery-delay-ms:5000}",scheduler="chipScheduler")public void deliver(){if(!settings.schedule||!bot.enabled)return;var client=clients.getIfAvailable();if(client==null)return;mapper.expire();mapper.expirePending();NotificationRow row=null;for(String chat:bot.pollTargets("chips")){row=mapper.claim(chat,UUID.randomUUID().toString());if(row!=null)break;}if(row==null)return;NotificationClient.Delivery result;try{result=client.send(row.targetChatId,row.payload);}catch(Exception e){result=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED");}String status=switch(result.outcome()){case SUCCESS->"SUCCESS";case UNKNOWN->"UNKNOWN";case REJECTED->"FAILED";case RETRY->row.attempts>=3?"FAILED":"RETRY";};mapper.delivered(row.id,row.claimToken,status,status.equals("SUCCESS")?null:"DELIVERY_"+result.outcome());}
    }
}
