package com.stocksentinel.custom;
import com.fasterxml.jackson.databind.*;import com.stocksentinel.jobs.TradingDatePolicy;import com.stocksentinel.market.MarketContracts.Adjustment;
import com.stocksentinel.stock.WatchlistMapper;import com.stocksentinel.notification.*;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.transaction.annotation.*;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;

public class CustomStrategyService {
    private final CustomStrategyMapper mapper;private final CustomFactorReader factors;private final WatchlistMapper stocks;private final TradingDatePolicy dates;private final ObjectMapper json;private final NotificationSettings bot;private final boolean notifications;
    public record Save(Integer expectedRevision,String name,String description,JsonNode definition){}
    public record State(Integer revision,Boolean enabled){}
    public record Execute(Integer revision,LocalDate tradeDate,List<String> symbols,@com.fasterxml.jackson.annotation.JsonProperty("notify")Boolean notifyGroups,String requestId){}
    public CustomStrategyService(CustomStrategyMapper mapper,CustomFactorReader factors,WatchlistMapper stocks,TradingDatePolicy dates,ObjectMapper json,NotificationSettings bot,boolean notifications){this.mapper=mapper;this.factors=factors;this.stocks=stocks;this.dates=dates;this.json=json;this.bot=bot;this.notifications=notifications;}
    static ResponseStatusException problem(HttpStatus status,String code){return new ResponseStatusException(status,code);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("INVALID_CUSTOM_REQUEST");}}
    private JsonNode node(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("CUSTOM_SNAPSHOT_INVALID");}}
    private CustomStrategyMapper.Header required(UUID id,boolean lock){var h=lock?mapper.locked(id):mapper.header(id);if(h==null)throw problem(HttpStatus.NOT_FOUND,"CUSTOM_STRATEGY_NOT_FOUND");return h;}
    public Object list(){var out=new LinkedHashMap<String,Object>();out.put("enabled",true);out.put("notificationsEnabled",notifications&&bot.enabled);out.put("automaticScan",false);out.put("strategies",mapper.list());out.put("maxSymbols",100);out.put("suggestedDate",mapper.suggestedDate(dates.today()));return out;}
    public Object detail(UUID id){var h=required(id,false);var r=mapper.revision(id,h.currentRevision);return Map.of("id",id,"currentRevision",h.currentRevision,"activeRevision",h.activeRevision==null?0:h.activeRevision,"name",r.name,"description",r.description,"definition",node(r.definition),"validated",mapper.validated(id,r.revision)>0,"revisions",mapper.revisions(id),"runs",mapper.runs(id).stream().map(this::node).toList());}
    private void validate(Save request){if(request==null||request.name()==null||request.name().isBlank()||request.name().length()>80||request.name().codePoints().anyMatch(Character::isISOControl)||request.description()!=null&&request.description().length()>500)throw new IllegalArgumentException("INVALID_CUSTOM_NAME");CustomRuleEngine.validate(request.definition());}
    @Transactional public Object save(UUID id,Save request){validate(request);int revision;
        if(id==null){if(request.expectedRevision()!=null&&request.expectedRevision()!=0)throw problem(HttpStatus.CONFLICT,"REVISION_CONFLICT");id=UUID.randomUUID();mapper.insert(id);revision=1;}
        else{var h=required(id,true);if(request.expectedRevision()==null||request.expectedRevision()!=h.currentRevision)throw problem(HttpStatus.CONFLICT,"REVISION_CONFLICT");revision=h.currentRevision+1;}
        var row=new CustomStrategyMapper.Revision();row.strategyId=id;row.revision=revision;row.name=request.name().trim();row.description=request.description()==null?"":request.description();row.definition=encode(request.definition());mapper.revisionInsert(row);mapper.current(id,revision);return detail(id);
    }
    @Transactional public Object state(UUID id,State request){var h=required(id,true);if(request==null||request.enabled()==null||request.revision()==null||request.revision()!=h.currentRevision)throw problem(HttpStatus.CONFLICT,"REVISION_CONFLICT");if(request.enabled()&&mapper.validated(id,request.revision())==0)throw problem(HttpStatus.CONFLICT,"PREVIEW_VALIDATION_REQUIRED");mapper.active(id,request.enabled()?request.revision():null);return detail(id);}
    private List<String> scope(JsonNode definition,List<String> samples,boolean preview){
        if(samples!=null){if(!preview)throw new IllegalArgumentException("SCAN_SCOPE_IS_FROZEN");return CustomRuleEngine.symbols(json.valueToTree(samples),5);}
        var result=switch(definition.path("universe").asText()){case "WATCHLIST"->mapper.watchlist();case "POSITIONS"->mapper.positions();case "SYMBOLS"->CustomRuleEngine.symbols(definition.path("symbols"),100);default->throw new IllegalArgumentException("INVALID_UNIVERSE");};
        if(result.isEmpty()||result.size()>100)throw problem(HttpStatus.CONFLICT,"CUSTOM_SCOPE_EMPTY_OR_OVER_100");return result;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ) public Object execute(UUID id,Execute request,boolean preview){
        var h=required(id,true);if(request==null||request.revision()==null||request.tradeDate()==null||request.tradeDate().isAfter(dates.today())||!"OPEN".equals(dates.calendarStatus(request.tradeDate())))throw new IllegalArgumentException("OPEN_PAST_OR_CONFIRMED_DATE_REQUIRED");
        var revision=mapper.revision(id,request.revision());if(revision==null)throw problem(HttpStatus.NOT_FOUND,"CUSTOM_REVISION_NOT_FOUND");var definition=node(revision.definition);CustomRuleEngine.validate(definition);
        var day=request.tradeDate();boolean notify=Boolean.TRUE.equals(request.notifyGroups());String hash=null;
        if(preview&&notify)throw new IllegalArgumentException("PREVIEW_CANNOT_NOTIFY");
        if(!preview&&request.symbols()!=null)throw new IllegalArgumentException("SCAN_SCOPE_IS_FROZEN");
        if(!preview){if(request.requestId()==null||!request.requestId().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("REQUEST_ID_REQUIRED");try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(List.of(request.revision(),day,notify)).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException();}var previous=mapper.request(id,request.requestId());if(previous!=null){if(!hash.equals(previous.get("request_hash")))throw problem(HttpStatus.CONFLICT,"REQUEST_SCOPE_CONFLICT");return node((String)previous.get("results"));}
            if(!Objects.equals(h.activeRevision,request.revision()))throw problem(HttpStatus.CONFLICT,"ACTIVE_REVISION_REQUIRED");
            if(notify&&(!notifications||!bot.enabled))throw problem(HttpStatus.CONFLICT,"CUSTOM_NOTIFICATIONS_DISABLED");
            if(notify&&!day.equals(dates.today()))throw problem(HttpStatus.CONFLICT,"HISTORICAL_NOTICES_DISABLED");
        }
        if(day.equals(dates.today())&&!dates.allowToday(day))throw problem(HttpStatus.CONFLICT,"CLOSE_CONFIRMATION_REQUIRED");
        var symbols=scope(definition,request.symbols(),preview);var results=new ArrayList<Map<String,Object>>();int matched=0,skipped=0,events=0,notices=0;
        for(String symbol:symbols){var stock=stocks.stock(symbol);var row=new LinkedHashMap<String,Object>();row.put("symbol",symbol);row.put("name",stock==null?"未知股票":stock.name);row.put("tradeDate",day);row.put("adjustment",definition.path("adjustment").asText());row.put("ruleVersion","custom-daily-dsl-v1");row.put("calculationVersion","ma-macd-vol-v1");
            if(stock==null||!"ACTIVE".equals(stock.status)||!Set.of("SH","SZ").contains(stock.exchange)||stock.name.toUpperCase(Locale.ROOT).contains("ST")){row.put("status","SKIPPED");row.put("code",stock==null?"STOCK_NOT_FOUND":"ST_OR_INACTIVE");skipped++;results.add(row);continue;}
            var snapshot=factors.read(symbol,day,Adjustment.valueOf(definition.path("adjustment").asText()));
            if(day.equals(dates.today())&&(snapshot.source()==null||!dates.confirmedSource(day,snapshot.source()))){row.put("status","SKIPPED");row.put("code","SOURCE_CLOSE_CONFIRMATION_REQUIRED");skipped++;results.add(row);continue;}
            var decision=CustomRuleEngine.evaluate(definition,snapshot.values());row.put("status",decision.status());row.put("code",decision.status().equals("SKIPPED")?snapshot.code().equals("AVAILABLE")?"REQUIRED_FACTOR_UNAVAILABLE":snapshot.code():"RULES_EVALUATED");row.put("source",snapshot.source());row.put("conditions",decision.conditions());results.add(row);
            if(decision.status().equals("SKIPPED")){skipped++;continue;}
            if(decision.status().equals("MATCHED")){matched++;if(!preview){UUID event=UUID.randomUUID();int inserted=mapper.event(event,id,request.revision(),symbol,day,encode(row));events+=inserted;if(inserted==0)event=mapper.eventId(id,request.revision(),symbol,day);if(notify)for(String chat:bot.chatIds)notices+=mapper.notice(event,chat,message(revision,row,day));}}
        }
        UUID run=UUID.randomUUID();var out=new LinkedHashMap<String,Object>();out.put("runId",run);out.put("strategyId",id);out.put("revision",request.revision());out.put("tradeDate",day);out.put("kind",preview?"PREVIEW":"SCAN");out.put("scope",symbols);out.put("matched",matched);out.put("skipped",skipped);out.put("evaluated",symbols.size()-skipped);out.put("newEvents",events);out.put("notificationsQueued",notices);out.put("results",results);out.put("note","只使用本地已落库数据；缺失因子整股跳过。预览不是回测，命中不代表买卖建议。入队不代表送达。");
        mapper.run(run,id,request.revision(),day,preview?"PREVIEW":"SCAN",preview?null:request.requestId(),hash,encode(out),symbols.size()-skipped,skipped,matched);return out;
    }
    private String message(CustomStrategyMapper.Revision revision,Map<String,Object> row,LocalDate day){String name=revision.name.replaceAll("[<>`*_]"," ");return MessageIdentity.header(MessageIdentity.Kind.CANDIDATE,"自定义日线条件命中",name+" · v"+revision.revision,"公共观察","人工扫描·收盘数据")+"建议操作：核对策略条件与风险，作为观察候选。\n股票："+String.valueOf(row.get("name")).replaceAll("[\\p{Cntrl}<>`*_]"," ")+" "+row.get("symbol")+"\n日线依据："+day+"；复权："+row.get("adjustment")+"\n策略由用户配置，已按保存版本评估；缺失因子不触发。\n未回测，不自动关注或下单。";}
}
