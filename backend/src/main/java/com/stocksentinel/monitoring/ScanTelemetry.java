package com.stocksentinel.monitoring;

import com.stocksentinel.intraday.IntradayContracts.Snapshot;
import java.time.*;
import org.springframework.stereotype.Service;

/** Diagnostics are best effort and cannot change a committed rule outcome. Never accepts exception text. */
@Service
public class ScanTelemetry {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ScanTelemetry.class);
    private final ScanTelemetryMapper mapper;
    public ScanTelemetry(ScanTelemetryMapper mapper){this.mapper=mapper;}
    public void record(long job,String symbol,String stage,String status,String code,OffsetDateTime started,OffsetDateTime finished,long elapsed,Object data){
        try{
            var row=new ScanTelemetryMapper.Step();row.jobId=job;row.symbol=symbol;row.stage=stage;row.status=status;
            row.code=code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"DIAGNOSTIC_CODE_UNAVAILABLE";
            row.startedAt=started;row.finishedAt=finished;row.elapsedMs=Math.max(0,elapsed);
            if(data instanceof Snapshot snapshot){row.source=snapshot.source()!=null&&snapshot.source().matches("[A-Z0-9_]{1,40}")?snapshot.source():null;
                row.dataObservedAt=snapshot.observedAt();if(snapshot.bars()!=null&&!snapshot.bars().isEmpty()&&snapshot.bars().getLast()!=null)row.dataBasisAt=snapshot.bars().getLast().barAt();}
            if(data instanceof com.stocksentinel.personal.TObservationRules.Rsi rsi){row.source=rsi.source()!=null&&rsi.source().matches("[A-Z0-9_]{1,40}")?rsi.source():null;row.dataObservedAt=rsi.observedAt();row.dataBasisAt=rsi.fiveAt();}
            mapper.insert(row);
        }catch(Exception unavailable){log.warn("scan_diagnostic_write_failed job={} stage={}",job,stage);}
    }
    public void link(long job,long signal){try{mapper.link(job,signal);}catch(Exception unavailable){log.warn("scan_signal_link_failed job={}",job);}}
    public static String category(String status,String code){
        if("UNKNOWN".equals(status))return "DELIVERY_UNKNOWN";
        if("PENDING".equals(status)||"RETRY".equals(status)||"SENDING".equals(status))return "DELIVERY_QUEUED";
        if(code==null)return "UNCLASSIFIED";
        if(code.contains("STALE"))return "DATA_STALE";
        if(code.contains("INSUFFICIENT")||code.contains("NO_MINUTE_DATA"))return "DATA_INSUFFICIENT";
        if(code.contains("HTTP")||code.contains("TIMEOUT")||code.contains("CONNECTION")||code.contains("SOURCE_UNAVAILABLE"))return "SOURCE_FAILURE";
        if(code.contains("OUTSIDE_SESSION")||code.contains("SESSION_ENDED")||code.contains("MARKET_CLOSED"))return "OUTSIDE_SESSION";
        if(code.contains("ALREADY")||code.contains("NO_NEW"))return "NO_NEW_EVENT";
        if(code.contains("NO_RESONANCE")||code.contains("NO_RULE"))return "RULE_NOT_MATCHED";
        return "SUCCESS".equals(status)?"COMPLETED":"SKIPPED".equals(status)?"SKIPPED":"FAILED".equals(status)?"FAILED":"UNCLASSIFIED";
    }
}
