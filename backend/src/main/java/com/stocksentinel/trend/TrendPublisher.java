package com.stocksentinel.trend;
import com.stocksentinel.notification.NotificationSettings;import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;
/** Signal and all destination receipts commit together. */
public class TrendPublisher {
    private final TrendMapper mapper;private final NotificationSettings bot;
    public TrendPublisher(TrendMapper mapper,NotificationSettings bot){this.mapper=mapper;this.bot=bot;}
    @Transactional public void stock(String symbol,LocalDate date,String phase,String snapshot,String text){if(mapper.event(symbol,date,phase,snapshot)==1)send("stock:"+date+":"+symbol+":"+phase,text);}
    private void send(String key,String text){if(bot.enabled)for(String chat:bot.chatIds)mapper.notice("trend-public:"+key+":"+bot.destinationHash(chat),chat,text);}
    @Transactional public void market(String state,String snapshot,String text){send("market:"+state,text);mapper.saveRuntime("market-state",snapshot);}
}
