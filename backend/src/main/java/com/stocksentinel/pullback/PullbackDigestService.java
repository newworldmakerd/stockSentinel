package com.stocksentinel.pullback;

import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.report.*;
import com.stocksentinel.notification.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class PullbackDigestService {
    @org.springframework.beans.factory.annotation.Autowired private ScreeningService screening;
    private final PullbackService pullback;
    private final PullbackDigestQueue queue;
    private final PullbackDigestMapper mapper;private final PullbackSettings strategy;private final ReportMapper reports;
    private final DailyReportService daily;private final NotificationSettings bot;private final ObjectProvider<NotificationClient> clients;
    private final TradingDatePolicy dates;private final Clock clock;private final boolean enabled,worker;
    public PullbackDigestService(PullbackDigestMapper mapper,PullbackDigestQueue queue,PullbackSettings strategy,PullbackService pullback,ReportMapper reports,DailyReportService daily,
            NotificationSettings bot,ObjectProvider<NotificationClient> clients,TradingDatePolicy dates,Clock clock,
            @Value("${pullback.digest-enabled:false}") boolean enabled,@Value("${pullback.digest-worker-enabled:true}") boolean worker) {
        this.mapper=mapper;this.queue=queue;this.strategy=strategy;this.pullback=pullback;this.reports=reports;this.daily=daily;this.bot=bot;this.clients=clients;
        this.dates=dates;this.clock=clock;this.enabled=enabled;this.worker=worker;
    }
    public static boolean session(LocalTime time) {
        return (!time.isBefore(LocalTime.of(9,40)) && time.isBefore(LocalTime.of(11,31)))
            || (!time.isBefore(LocalTime.of(13,10)) && time.isBefore(LocalTime.of(15,1)));
    }
    private boolean allowed(OffsetDateTime now){return enabled && strategy.enabled() && bot.enabled && deliveryWindow(now.toLocalTime()) && "OPEN".equals(dates.calendarStatus(now.toLocalDate()));}
    public static boolean deliveryWindow(LocalTime time) {
        return (!time.isBefore(LocalTime.of(9,40))&&time.isBefore(LocalTime.of(9,50)))
            ||(!time.isBefore(LocalTime.of(15,30))&&time.isBefore(LocalTime.of(15,40)));
    }
    /** Preserve the existing data-preparation retry cadence independently of message frequency. */
    @Scheduled(cron="0 */10 * * * *",zone="Asia/Shanghai",scheduler="reportTaskScheduler")
    public void refreshScreening() {
        var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
        if(enabled&&strategy.enabled()&&bot.enabled&&session(now.toLocalTime())&&"OPEN".equals(dates.calendarStatus(now.toLocalDate()))
                &&screening!=null&&!screening.start())screening.retry();
    }
    @Scheduled(cron="0 40 9 * * *",zone="Asia/Shanghai",scheduler="reportTaskScheduler")
    @Scheduled(cron="0 30 15 * * *",zone="Asia/Shanghai",scheduler="reportTaskScheduler")
    public void tick() {
        var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
        if(!allowed(now)||!Set.of(LocalTime.of(9,40),LocalTime.of(15,30)).contains(now.toLocalTime().withSecond(0).withNano(0)))return;
        var slot=now.withSecond(0).withNano(0);
        String[] payload={null};
        for(String hash:bot.destinationHashes())queue.enqueue(slot,hash,()->{if(payload[0]==null)payload[0]=render(slot);return payload[0];});
    }
    public String render(OffsetDateTime slot) {
        var run=reports.run(slot.toLocalDate());var previous=reports.previousOpen(slot.toLocalDate());
        var b=new StringBuilder(com.stocksentinel.notification.MessageIdentity.header(com.stocksentinel.notification.MessageIdentity.Kind.CANDIDATE,"缩量回踩·早晚候选","强势股缩量回踩","全市场候选","日线筛选·未转强")).append(slot.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n范围：全市场沪深A股；排除ST、无成交及历史/流动性不足。\n");
        if(previous==null || run==null || !previous.equals(run.asOf))return b.append("当期日线筛选数据尚未就绪，暂无可核验候选。\n不自动下单。").toString();
        long processed=0,pending=0,failed=0;
        for(var row:reports.coverage(slot.toLocalDate())){long count=((Number)row.get("count")).longValue();if("PENDING".equals(row.get("status")))pending+=count;else processed+=count;if("FAILED".equals(row.get("status")))failed+=count;}
        b.append("日线依据 ").append(previous).append("；已处理 ").append(processed).append('/').append(run.total).append("，待处理 ").append(pending).append("，失败 ").append(failed).append("。\n");
        var candidates=pullback.pool();
        if(candidates.isEmpty())b.append("当前已处理范围暂无符合条件的股票；不强行推荐。\n");
        int rank=0;for(var c:candidates){if(rank==5)break;
            b.append(++rank).append(". ").append(clean(c.name)).append(' ').append(c.symbol)
                .append("\n已满足强势、缩量回调及MA20条件；观察突破价 ").append(c.referenceHigh).append(" 元（前日不复权高点）。\n");
        }
        if(pending>0 || failed>0 || !"COMPLETED".equals(run.status))b.append("筛选尚未完整覆盖全市场，以上为阶段性结果。\n");
        return b.append("按20日均成交额排序，最多5只；仅为观察池，尚不等于盘中转强。\n完整分钟收盘突破前日高点且量能≥").append(strategy.minuteMultiplier()).append("倍时另发即时提醒。\n仍在有效观察池的股票可以重复推荐；初始参数尚未回测，不自动关注或下单。").toString();
    }
    private static String clean(Object value){String s=String.valueOf(value).replaceAll("[\\p{Cntrl}<>`*]"," ");return s.length()>30?s.substring(0,30):s;}
    @Scheduled(fixedDelayString="${pullback.digest-poll-ms:5000}",scheduler="reportTaskScheduler")
    public void deliver(){if(worker)deliverOne();}
    public boolean deliverOne() {
        var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));if(!allowed(now))return false;
        var client=clients.getIfAvailable();if(client==null)return false;mapper.expire();
        PullbackDigestMapper.Delivery row=null;String target=null;
        for(String chat:bot.pollTargets("pullback")){row=mapper.claim(bot.destinationHash(chat),UUID.randomUUID().toString());if(row!=null){target=chat;break;}}if(row==null)return false;
        NotificationClient.Delivery result;try{result=client.send(target,row.payload);}catch(Exception failed){result=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED");}
        String status=switch(result.outcome()){case SUCCESS->"SUCCESS";case UNKNOWN->"UNKNOWN";case REJECTED->"FAILED";case RETRY->row.attempts>=3?"FAILED":"RETRY";};
        mapper.finish(row.id,row.claimToken,status,"SUCCESS".equals(status)?null:"DELIVERY_"+result.outcome());return true;
    }
}
