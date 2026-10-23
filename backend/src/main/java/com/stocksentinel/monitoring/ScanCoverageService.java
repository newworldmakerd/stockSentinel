package com.stocksentinel.monitoring;

import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksentinel.trend.TrendMapper;import com.stocksentinel.report.ReportMapper;import com.stocksentinel.jobs.TradingDatePolicy;import com.stocksentinel.stock.WatchlistMapper;
import java.time.*;import java.util.*;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanCoverageService {
    private final ScanCoverageMapper mapper;private final TrendMapper trend;private final ReportMapper reports;private final ScanRecoveryMapper recovery;
    private final TradingDatePolicy dates;private final WatchlistMapper stocks;private final ObjectMapper json;private final Clock clock;
    public ScanCoverageService(ScanCoverageMapper mapper,TrendMapper trend,ReportMapper reports,ScanRecoveryMapper recovery,TradingDatePolicy dates,WatchlistMapper stocks,ObjectMapper json,Clock clock){this.mapper=mapper;this.trend=trend;this.reports=reports;this.recovery=recovery;this.dates=dates;this.stocks=stocks;this.json=json;this.clock=clock;}
    private JsonNode read(String value){try{return value==null?null:json.readTree(value);}catch(Exception e){throw new IllegalStateException("COVERAGE_STATE_INVALID");}}
    private void save(String key,JsonNode value){try{trend.saveRuntime(key,json.writeValueAsString(value));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("COVERAGE_STATE_INVALID");}}
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    private String fingerprint(List<String> keys){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(",",keys.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("COVERAGE_STATE_INVALID");}}
    @Transactional public JsonNode begin(LocalDate date,String phase,List<String> keys){
        var current=read(trend.runtime("quote-cycle-current"));String hash=fingerprint(keys);
        if(current!=null&&date.toString().equals(current.path("tradeDate").asText())&&phase.equals(current.path("phase").asText())&&hash.equals(current.path("universeHash").asText())&&"RUNNING".equals(current.path("status").asText()))return current;
        if(current!=null&&"RUNNING".equals(current.path("status").asText())){var old=(ObjectNode)current.deepCopy();old.put("status","SUPERSEDED").put("reason","DATE_PHASE_OR_UNIVERSE_CHANGED").put("endedAt",now().toString());save("quote-cycle-last",old);}
        var cycle=json.createObjectNode().put("id",UUID.randomUUID().toString()).put("tradeDate",date.toString()).put("phase",phase).put("universeHash",hash).put("total",keys.size()).put("status","RUNNING").put("startedAt",now().toString()).put("attempted",0).put("valid",0).put("failed",0).put("skipped",0).put("evaluated",0).put("attemptCoverageComplete",false).put("allQuotesValid",false);
        save("quote-cycle-current",cycle);return cycle;
    }
    public Set<String> covered(JsonNode cycle){return mapper.covered(LocalDate.parse(cycle.path("tradeDate").asText()),cycle.path("phase").asText(),cycle.path("id").asText());}
    @Transactional public void record(JsonNode cycle,List<ScanCoverageMapper.Quote> items,String cursor){
        if(!items.isEmpty())mapper.record(LocalDate.parse(cycle.path("tradeDate").asText()),cycle.path("phase").asText(),cycle.path("id").asText(),now(),items);
        if(cursor!=null)trend.saveRuntime("quote-cursor","{\"key\":\""+cursor+"\"}");
    }
    @Transactional public JsonNode finish(JsonNode cycle){
        var counts=mapper.cycleCounts(LocalDate.parse(cycle.path("tradeDate").asText()),cycle.path("phase").asText(),cycle.path("id").asText());var result=(ObjectNode)cycle.deepCopy();counts.forEach((k,v)->result.put(k,((Number)v).longValue()));
        boolean complete=result.path("attempted").asInt()>=result.path("total").asInt(),valid=result.path("valid").asInt()>=result.path("total").asInt();result.put("attemptCoverageComplete",complete).put("allQuotesValid",valid).put("updatedAt",now().toString()).put("elapsedSeconds",Math.max(0,Duration.between(OffsetDateTime.parse(result.path("startedAt").asText()),now()).getSeconds())).put("status",complete?(valid?"COMPLETED":"PARTIAL"):"RUNNING");
        if(complete){result.put("endedAt",now().toString());save("quote-cycle-last",result);}save("quote-cycle-current",result);return result;
    }
    public JsonNode current(LocalDate date){var value=read(trend.runtime("quote-cycle-current"));return value!=null&&date.toString().equals(value.path("tradeDate").asText())?value:null;}
    public Object summary(LocalDate date){var day=date==null?dates.today():date;var basis=reports.previousOpen(day);var out=new LinkedHashMap<String,Object>();out.put("tradeDate",day);out.put("historyBasis",basis);out.put("quoteCycle",current(day));out.put("lastQuoteCycle",read(trend.runtime("quote-cycle-last")));out.put("quoteStates",mapper.quoteSummary(day));out.put("trendHistory",basis==null?List.of():trend.coverage(basis));out.put("screening",reports.coverage(day));out.put("retries",recovery.summary(day));out.put("historyRetries",basis==null?List.of():recovery.summary(basis));out.put("note","完整轮转表示每个标的均已尝试，不代表行情均有效或全部符合策略。成功时间缺失表示尚未记录；不触发行情、模型或通知。");return out;}
    public Object stock(String symbol,LocalDate date){if(symbol==null||!symbol.matches("[0-9]{6}"))throw new IllegalArgumentException("INVALID_SYMBOL");var s=stocks.stock(symbol);if(s==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"STOCK_NOT_FOUND");var day=date==null?dates.today():date;var basis=reports.previousOpen(day);var out=new LinkedHashMap<String,Object>();out.put("symbol",symbol);out.put("name",s.name);out.put("tradeDate",day);out.put("historyBasis",basis);out.put("quotes",mapper.stockQuotes(day,("SH".equals(s.exchange)?"sh":"sz")+symbol));out.put("trendHistory",basis==null?null:mapper.trendHistory(basis,symbol));out.put("screening",mapper.screening(day,symbol));out.put("note","历史成功时间未回填或猜测；旧READY记录可结合updated_at判断原准备记录时间。行情校验成功不等于规则命中。");return out;}
    public Object failures(String pipeline,LocalDate date,int limit,int offset){if(limit<1||limit>100||offset<0||offset>100000)throw new IllegalArgumentException("INVALID_PAGE");var day=date==null?dates.today():date;return switch(pipeline){case "TREND_QUOTES"->mapper.quoteFailures(day,limit,offset);case "TREND_HISTORY"->mapper.historyFailures(date==null?reports.previousOpen(day):day,limit,offset);case "SCREENING"->mapper.screeningFailures(day,limit,offset);default->throw new IllegalArgumentException("INVALID_PIPELINE");};}
}
