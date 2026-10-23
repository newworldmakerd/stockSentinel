package com.stocksentinel.chips;
import java.time.LocalDate;import org.springframework.transaction.annotation.Transactional;
import com.stocksentinel.notification.NotificationSettings;
public class ChipWriter {
    private final ChipMapper mapper;private final NotificationSettings bot;
    public ChipWriter(ChipMapper mapper,NotificationSettings bot){this.mapper=mapper;this.bot=bot;}
    @Transactional public void begin(LocalDate date,String parameters){if(mapper.start(date,parameters)==1)mapper.seed(date);}
    @Transactional public void complete(LocalDate date,String status,String text){if(mapper.finish(date,status)==1&&bot.enabled)for(String chat:bot.chatIds)mapper.notice("chips:"+date+":"+bot.destinationHash(chat),chat,text);}
}
