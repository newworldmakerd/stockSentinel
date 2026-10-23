package com.stocksentinel.monitoring;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Only operational metadata: no user, group, payload, holdings, tool text or idempotency keys. */
@RestController
@RequestMapping("/api/v1/monitoring")
public class ScanDiagnosticsController {
    private static final Set<String> STEP_FIELDS=Set.of("job_id","symbol","stage","status","code","source","data_observed_at","data_basis_at","started_at","finished_at","elapsed_ms");
    private static final Set<String> TRACE_FIELDS=Set.of("id","message_type","status","attempts","queued_at","delivery_confirmed_at","delivery_code","signal_recorded_at","strategy_code","strategy_version","signal_basis_at","observation_job_id","observation_version","observation_basis_at","indicator_observed_at","evaluated_at","indicator_source");
    private final ScanTelemetryMapper mapper;
    public ScanDiagnosticsController(ScanTelemetryMapper mapper){this.mapper=mapper;}
    @GetMapping("/jobs/{id}/steps") public Object steps(@PathVariable long id){
        if(id<1||!mapper.exists(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SCAN_JOB_NOT_FOUND");
        return mapper.steps(id).stream().map(row->{var out=safe(row,STEP_FIELDS);out.put("category",ScanTelemetry.category((String)row.get("status"),(String)row.get("code")));return out;}).toList();
    }
    @GetMapping("/notifications/{id}/trace") public Object trace(@PathVariable long id){
        var row=id>0?mapper.trace(id):null;if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND");
        var out=safe(row,TRACE_FIELDS);out.put("category",ScanTelemetry.category((String)row.get("status"),(String)row.get("delivery_code")));out.put("scanJobs",mapper.jobs(id));
        out.put("note","时间缺失表示历史未记录或不适用；发送确认不等于用户已读，不包含持仓或成交正文。");return out;
    }
    private Map<String,Object> safe(Map<String,Object> row,Set<String> fields){var out=new LinkedHashMap<String,Object>();row.forEach((key,value)->{if(fields.contains(key))out.put(key,value);});return out;}
}
