package com.stocksentinel.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;import com.stocksentinel.jobs.TradingDatePolicy;import com.stocksentinel.report.ReportMapper;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;

@Service
public class ScanRecoveryService {
    public enum Pipeline {TREND_HISTORY,SCREENING}
    private final ScanRecoveryMapper mapper;private final TradingDatePolicy dates;private final ReportMapper reports;private final ObjectMapper json;
    public ScanRecoveryService(ScanRecoveryMapper mapper,TradingDatePolicy dates,ReportMapper reports,ObjectMapper json){this.mapper=mapper;this.dates=dates;this.reports=reports;this.json=json;}
    public record Request(Pipeline pipeline,LocalDate date,List<String> symbols,String requestId,Boolean dryRun){}
    private List<String> validate(Request r){
        if(r==null||r.pipeline()==null||r.date()==null||r.date().isAfter(dates.today())||!"OPEN".equals(dates.calendarStatus(r.date()))||r.symbols()==null||r.symbols().isEmpty()||r.symbols().size()>20||r.symbols().stream().anyMatch(s->s==null||!s.matches("[0-9]{6}"))||new HashSet<>(r.symbols()).size()!=r.symbols().size())throw new IllegalArgumentException("INVALID_RETRY_SCOPE");
        if(r.pipeline()==Pipeline.SCREENING&&(!r.date().equals(dates.today())||reports.run(r.date())==null))throw new IllegalArgumentException("CURRENT_SCREENING_REQUIRED");
        if(r.pipeline()==Pipeline.TREND_HISTORY&&r.date().equals(dates.today())&&!dates.allowToday(r.date()))throw new IllegalArgumentException("CLOSE_CONFIRMATION_REQUIRED");
        return r.symbols().stream().sorted().toList();
    }
    private String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception e){throw new IllegalArgumentException("INVALID_RETRY_SCOPE");}}
    private String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("RETRY_HASH_UNAVAILABLE");}}
    private List<Map<String,Object>> items(Request r,List<String> symbols){return r.pipeline()==Pipeline.TREND_HISTORY?mapper.trendItems(r.date(),symbols):mapper.screenItems(r.date(),symbols);}
    public Object preview(Request r){var symbols=validate(r);return Map.of("dryRun",true,"pipeline",r.pipeline(),"date",r.date(),"symbols",symbols,"items",items(r,symbols),"note","仅FAILED可补跑；最多20只；READY/CANDIDATE/EXCLUDED及历史通知不重置。趋势补跑追加一次尝试，不清零自动重试次数。");}
    @Transactional public Object queue(Request r){
        var symbols=validate(r);if(r.requestId()==null||!r.requestId().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("REQUEST_ID_REQUIRED");
        String hash=hash(encode(List.of(r.pipeline(),r.date(),symbols)));var existing=mapper.request(r.requestId());
        if(existing!=null){if(!hash.equals(existing.get("scope_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"RETRY_ID_SCOPE_CONFLICT");return result(r.requestId());}
        mapper.requestInsert(r.requestId(),r.pipeline().name(),r.date(),hash,encode(symbols),encode(items(r,symbols)));
        existing=mapper.request(r.requestId());if(!hash.equals(existing.get("scope_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"RETRY_ID_SCOPE_CONFLICT");
        if(r.pipeline()==Pipeline.TREND_HISTORY)mapper.queueTrend(r.requestId(),r.date(),symbols);else if(mapper.queueScreening(r.requestId(),r.date(),symbols)>0)reports.reopen(r.date());
        return result(r.requestId());
    }
    public Object result(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("INVALID_REQUEST_ID");var request=mapper.request(id);if(request==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"RETRY_REQUEST_NOT_FOUND");var out=new LinkedHashMap<String,Object>();out.put("requestId",id);out.put("pipeline",request.get("pipeline"));out.put("date",request.get("run_date"));out.put("scope",request.get("scope"));out.put("before",request.get("before_state"));out.put("items",mapper.results(id));out.put("note","只入队，不强制视为成功；等待正常采集/校验，结果在items更新。补跑不补发历史通知。");return out;}
}
