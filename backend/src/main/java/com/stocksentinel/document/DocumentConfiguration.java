package com.stocksentinel.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.pullback.PullbackSettings;
import com.stocksentinel.report.ReportMapper;
import com.stocksentinel.stock.WatchlistMapper;
import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wecom-doc.enabled",havingValue="true")
public class DocumentConfiguration {
    @Bean DocumentMcpClient documentMcpClient(DocumentSettings settings,ObjectMapper json){return new DocumentMcpClient(settings.endpoint(),json);}
    @Bean StockDocumentSnapshot stockDocumentSnapshot(DocumentArchiveMapper mapper,ReportMapper reports,WatchlistMapper watches,PullbackSettings pullback,ObjectMapper json){return new StockDocumentSnapshot(mapper,reports,watches,pullback,json);}
    @Bean DocumentArchiveService documentArchiveService(DocumentArchiveMapper mapper,DocumentMcpClient client,StockDocumentSnapshot snapshot,TradingDatePolicy dates,ObjectMapper json){return new DocumentArchiveService(mapper,client,snapshot,dates,json);}
    @Bean(name="documentTaskScheduler") ThreadPoolTaskScheduler documentTasks(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("stock-document-");return scheduler;}
    @Bean DocumentSchedule documentSchedule(DocumentSettings settings,DocumentArchiveService service,TradingDatePolicy dates,Clock clock){return new DocumentSchedule(settings,service,dates,clock);}
    public static class DocumentSchedule {
        private final DocumentSettings settings;private final DocumentArchiveService service;private final TradingDatePolicy dates;private final Clock clock;
        DocumentSchedule(DocumentSettings settings,DocumentArchiveService service,TradingDatePolicy dates,Clock clock){this.settings=settings;this.service=service;this.dates=dates;this.clock=clock;}
        @Scheduled(cron="0 */5 * * * *",zone="Asia/Shanghai",scheduler="documentTaskScheduler")
        public void tick(){
            if(!settings.scheduleEnabled)return;var time=LocalTime.now(clock.withZone(TradingDatePolicy.ZONE));
            if(time.isBefore(LocalTime.of(15,35))||time.isAfter(LocalTime.of(23,0))||!"OPEN".equals(dates.calendarStatus(dates.today())))return;
            try{service.export(dates.today(),false);}catch(Exception failed){org.slf4j.LoggerFactory.getLogger(DocumentSchedule.class).warn("stock_document_export_not_ready");}
        }
    }
}
