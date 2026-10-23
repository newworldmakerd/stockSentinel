package com.stocksentinel.monitoring;

import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.report.DailyReportService;
import com.stocksentinel.notification.NotificationSettings;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/** Observes committed scan results; enqueues operational messages without calling the model or bot. */
@Service
public class ScanFaultService {
    private static final Map<String,String> LABELS=Map.of("MINUTE_FETCH","分钟行情获取","MINUTE_DATA","分钟行情有效性",
        "PERSONAL_PRICE","个人价位处理","INTRADAY_WRITE","盘中信号处理或写库","PULLBACK_CONFIRM","回踩转强确认","MA60_RISK","盘中MA60风险处理","SCAN_JOB","扫描任务","T_RSI","做T历史行情与指标");
    private static final List<String> KINDS=List.of("MINUTE_FETCH","MINUTE_DATA","PERSONAL_PRICE","INTRADAY_WRITE","PULLBACK_CONFIRM","MA60_RISK","SCAN_JOB","T_RSI");
    private static final Set<String> BAD_DATA=Set.of("INVALID_MINUTE_RESPONSE","NO_MINUTE_DATA","INVALID_MINUTE_BARS","STALE_MINUTES","INVALID_MINUTE_METRICS","CROSS_SESSION_METRICS");
    private final ScanFaultMapper mapper;private final DailyReportService destination;private final NotificationSettings settings;
    private final boolean enabled;private final int threshold,cooldown;
    public ScanFaultService(ScanFaultMapper mapper,DailyReportService destination,NotificationSettings settings,
            @Value("${scan-alerts.enabled:false}") boolean enabled,@Value("${scan-alerts.failure-threshold:3}") int threshold,
            @Value("${scan-alerts.cooldown-minutes:15}") int cooldown) {
        if(threshold<1||threshold>60||cooldown<1||cooldown>1440)throw new IllegalArgumentException("Invalid scan alert limits");
        this.mapper=mapper;this.destination=destination;this.settings=settings;this.enabled=enabled;this.threshold=threshold;this.cooldown=cooldown;
    }
    @Transactional public void observe(long jobId) {
        if(!enabled||!settings.enabled)return;
        var scan=mapper.scan(jobId);if(scan==null||scan.startedAt==null)return;
        var faults=new LinkedHashMap<String,List<ScanFaultMapper.Item>>();
        for(var item:mapper.items(jobId)) {
            String kind=kind(item);
            if(kind!=null)faults.computeIfAbsent(kind,k->new ArrayList<>()).add(item);
        }
        if("FAILED".equals(scan.status)&&faults.isEmpty())faults.put("SCAN_JOB",List.of());
        if("CALENDAR_MISSING".equals(scan.errorMessage))faults.putIfAbsent("SCAN_JOB",List.of());
        boolean complete="SUCCESS".equals(scan.status)&&scan.processedCount!=null&&scan.processedCount>0;
        // All normal skips (including holidays, lunch, no current trades, and overlap) are neutral.
        if(faults.isEmpty()&&!complete)return;
        for(String hash:settings.destinationHashes()){mapper.lockDestination(hash);
        for(String kind:KINDS) {
            if("T_RSI".equals(kind)&&!faults.containsKey(kind)&&!mapper.tRsiEvaluated(jobId))continue;
            mapper.ensure(hash,kind);var state=mapper.lock(hash,kind);
            if(jobId<=state.lastJobId)continue;
            if(faults.containsKey(kind)) {
                boolean contiguous=sameSession(state.lastScanAt,scan.startedAt)
                    && !scan.startedAt.isBefore(state.lastScanAt)
                    && !scan.startedAt.isAfter(state.lastScanAt.plusMinutes(5));
                if(!contiguous)state.streak=0;
                if(state.streak==0&&!state.active)state.incidentId=UUID.randomUUID().toString();
                state.streak++;
                if(state.streak>=threshold && (state.lastAlertAt==null||!scan.startedAt.isBefore(state.lastAlertAt.plusMinutes(cooldown)))) {
                    state.seq++;enqueue(state,scan,"FAULT",faultMessage(kind,scan,state.streak,faults.get(kind)));
                    state.active=true;state.lastAlertAt=scan.startedAt;
                }
            } else {
                state.streak=0;
                if(complete&&state.active) {
                    mapper.cancelFault(hash,kind,state.incidentId);
                    state.seq++;enqueue(state,scan,"RECOVERY",com.stocksentinel.notification.MessageIdentity.header(com.stocksentinel.notification.MessageIdentity.Kind.SYSTEM,"扫描异常恢复","运行诊断（非选股）","监控范围","本轮恢复")+"类别："+LABELS.get(kind)
                        +"\n时间："+scan.startedAt.atZoneSameInstant(TradingDatePolicy.ZONE)
                        +"\n本轮盘中扫描完整成功，未记录扫描失败或跳过；任务 #"+jobId
                        +"。\n仅表示当前监控范围的本轮运行恢复，不代表股票无风险。");
                    state.active=false;
                }
            }
            state.lastJobId=jobId;state.lastScanAt=scan.startedAt;mapper.save(state);
        }
    }
    }
    private void enqueue(ScanFaultMapper.State state,ScanFaultMapper.Scan scan,String type,String payload) {
        var event=new ScanFaultMapper.Event();event.destinationHash=state.destinationHash;event.failureKind=state.failureKind;
        event.incidentId=state.incidentId;event.seq=state.seq;event.eventType=type;event.jobId=scan.id;event.payload=payload;mapper.enqueue(event);
    }
    private static boolean sameSession(OffsetDateTime a,OffsetDateTime b) {
        if(a==null)return false;var x=a.atZoneSameInstant(TradingDatePolicy.ZONE);var y=b.atZoneSameInstant(TradingDatePolicy.ZONE);
        return x.toLocalDate().equals(y.toLocalDate())&&(x.getHour()<12)==(y.getHour()<12);
    }
    static String kind(ScanFaultMapper.Item item) {
        if("SKIPPED".equals(item.status))return "T_RSI_STALE_OR_INVALID".equals(item.code)||"T_RSI_PRICE_NOT_ALIGNED".equals(item.code)?"T_RSI":BAD_DATA.contains(item.code)?"MINUTE_DATA":null;
        if(!"FAILED".equals(item.status))return null;
        String code=item.code==null?"":item.code;
        if(code.startsWith("T_RSI_"))return "T_RSI";
        if(code.startsWith("MINUTE_")||code.startsWith("MINUTE_FETCH_"))return "MINUTE_FETCH";
        if(code.startsWith("PERSONAL_PRICE_"))return "PERSONAL_PRICE";
        if(code.startsWith("INTRADAY_WRITE_"))return "INTRADAY_WRITE";
        if(code.startsWith("PULLBACK_CONFIRM_"))return "PULLBACK_CONFIRM";
        if(code.startsWith("MA60_RISK_"))return "MA60_RISK";
        return "SCAN_JOB";
    }
    private static String faultMessage(String kind,ScanFaultMapper.Scan scan,int streak,List<ScanFaultMapper.Item> items) {
        var text=new StringBuilder(com.stocksentinel.notification.MessageIdentity.header(com.stocksentinel.notification.MessageIdentity.Kind.SYSTEM,"扫描异常提醒","运行诊断（非选股）","监控范围","数据/任务异常")+"类别：").append(LABELS.get(kind)).append("\n时间：")
            .append(scan.startedAt.atZoneSameInstant(TradingDatePolicy.ZONE)).append("\n连续异常：").append(streak).append("轮；任务 #").append(scan.id);
        if(!items.isEmpty()) {
            text.append("\n本轮受影响股票：").append(items.size()).append("只（最多展示5只）");
            for(var item:items.stream().limit(5).toList())text.append("\n").append(clean(item.name)).append(" ")
                .append(item.symbol!=null&&item.symbol.matches("[0-9]{6}")?item.symbol:"代码未知").append("：").append(safeCode(item.code));
        } else text.append("\n错误码：").append("CALENDAR_MISSING".equals(scan.errorMessage)?"CALENDAR_MISSING":"INTRADAY_JOB_FAILED");
        return text.append("\n受影响数据或处理结果可能缺失，请勿将未收到股票信号理解为无风险；其余股票继续按原规则扫描。").toString();
    }
    private static String safeCode(String code) {
        return code!=null&&code.matches("[A-Z0-9_]{1,100}")&&(BAD_DATA.contains(code)||code.startsWith("MINUTE_")
            ||code.startsWith("INTRADAY_")||code.startsWith("PERSONAL_PRICE_")||code.startsWith("PULLBACK_CONFIRM_")||code.startsWith("MA60_RISK_")||code.startsWith("T_RSI_"))?code:"SCAN_FAILURE";
    }
    private static String clean(String name) {if(name==null)return "名称未记录";var value=name.replaceAll("[\\p{Cntrl}<>`*]"," ");return value.substring(0,Math.min(24,value.length()));}
}
