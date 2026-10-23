package com.stocksentinel.trend;
import com.fasterxml.jackson.databind.*;import com.stocksentinel.market.*;import com.stocksentinel.market.MarketContracts.*;
import com.stocksentinel.common.entity.MarketDailyBar;import com.stocksentinel.jobs.TradingDatePolicy;import com.stocksentinel.report.ReportMapper;import com.stocksentinel.notification.*;
import java.time.*;import java.math.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
public class TrendService {
    private static final String INDEX_EASTMONEY="TREND_CSI300_EASTMONEY",INDEX_TENCENT="TREND_CSI300_TENCENT";
    private final TrendMapper mapper;private final MarketMapper bars;private final MarketCollectionService collection;private final TrendClient client;private final TrendLedger ledger;
    private final TrendSettings settings;private final TradingDatePolicy dates;private final ReportMapper calendar;private final NotificationSettings bot;private final ObjectMapper json;private final Clock clock;
    private final TrendPublisher publisher;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.monitoring.ScanCoverageService coverage;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.monitoring.ScanRecoveryService recovery;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.monitoring.ScanRecoveryMapper retries;
    @org.springframework.beans.factory.annotation.Autowired private TrendPreparationWriter preparationWriter;
    private java.util.function.LongSupplier scanNanos=System::nanoTime;
    private final AtomicBoolean preparing=new AtomicBoolean(),scanning=new AtomicBoolean();
    public TrendService(TrendMapper mapper,MarketMapper bars,MarketCollectionService collection,TrendClient client,TrendLedger ledger,TrendSettings settings,TradingDatePolicy dates,ReportMapper calendar,NotificationSettings bot,ObjectMapper json,Clock clock,TrendPublisher publisher){this.publisher=publisher;this.mapper=mapper;this.bars=bars;this.collection=collection;this.client=client;this.ledger=ledger;this.settings=settings;this.dates=dates;this.calendar=calendar;this.bot=bot;this.json=json;this.clock=clock;}
    private JsonNode node(String s){try{return s==null?null:json.readTree(s);}catch(Exception invalid){throw new IllegalStateException("TREND_SNAPSHOT_INVALID");}}
    private String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception invalid){throw new IllegalStateException("TREND_SNAPSHOT_INVALID");}}
    public Object status(){LocalDate previous=calendar.previousOpen(dates.today());var out=new LinkedHashMap<String,Object>();out.put("enabled",settings.enabled);out.put("version","TREND_FOLLOW_V1/1.0.0");out.put("historyBasis",previous);out.put("coverage",previous==null?List.of():mapper.coverage(previous));var index=previous==null?null:node(mapper.index(previous));out.put("index",index);out.put("marketFilter",index==null?"PAUSE_DATA_UNAVAILABLE":TrendRules.market(index)?"ALLOW":"PAUSE_MARKET_WEAK");out.put("indexError",index!=null||previous==null?null:node(mapper.runtime("index-error:"+previous)));out.put("quoteCycle",coverage.current(dates.today()));out.put("lastScan",node(mapper.runtime("last-scan")));out.put("lastPrepare",node(mapper.runtime("last-prepare")));out.put("parameters",Map.of("minAmount",settings.minAmount,"riskRate",settings.riskRate,"stockCap",settings.stockCap,"portfolioCap",settings.portfolioCap,"initialAtr",settings.initialAtr,"trailingAtr",settings.trailingAtr));return out;}
    public Object retrySelected(com.stocksentinel.monitoring.ScanRecoveryService.Request request){if(!preparing.compareAndSet(false,true))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"TREND_PREPARING_BUSY");try{return recovery.queue(request);}finally{preparing.set(false);}}
    public boolean prepareQueued(){var day=retries.nextTrendDate();if(day==null)return false;prepare(day,false,true);return true;}
    public Object prepare(LocalDate day,boolean dryRun){return prepare(day,dryRun,false);}
    private Object prepare(LocalDate day,boolean dryRun,boolean manualOnly){
        if(day==null||day.isAfter(dates.today())||!"OPEN".equals(dates.calendarStatus(day)))throw new IllegalArgumentException("TREND_OPEN_DATE_REQUIRED");
        if(dryRun)return Map.of("dryRun",true,"asOf",day,"action","分批准备沪深全市场完整日线底座，不发送买入信号");
        if(day.equals(dates.today())&&!dates.allowToday(day))return Map.of("status","CLOSE_CONFIRMATION_REQUIRED");if(!preparing.compareAndSet(false,true))return Map.of("status","BUSY");
        long began=System.nanoTime();int processed=0;
        try{
            if(!manualOnly&&mapper.coverage(day).isEmpty())mapper.seed(day);
            if(!manualOnly&&mapper.index(day)==null){
                String errorKey="index-error:"+day;
                var lastFailure=node(mapper.runtime(errorKey));
                boolean backoff=lastFailure!=null&&lastFailure.has("retryAfter")&&OffsetDateTime.parse(lastFailure.path("retryAfter").asText()).isAfter(OffsetDateTime.now(clock));
                boolean today=day.equals(dates.today()),eastConfirmed=today&&dates.confirmedSource(day,INDEX_EASTMONEY),tencentConfirmed=today&&dates.confirmedSource(day,INDEX_TENCENT);
                if(today&&!eastConfirmed&&!tencentConfirmed)mapper.saveRuntime(errorKey,encode(Map.of("asOf",day,"code","INDEX_CONFIRMATION_REQUIRED")));
                else if(!backoff)try{
                    String selection=!today||eastConfirmed&&tencentConfirmed?"AUTO":eastConfirmed?"EASTMONEY":"TENCENT";
                    var idx=selection.equals("AUTO")?client.index(day,today):client.index(day,today,selection);
                    if(idx==null||!"sh000300".equals(idx.path("key").asText())||!Set.of(INDEX_EASTMONEY,INDEX_TENCENT).contains(idx.path("source").asText())||!day.toString().equals(idx.path("as_of").asText())||idx.path("history_size").asInt()<201||TrendRules.n(idx,"ma200").signum()<=0||TrendRules.n(idx,"close").signum()<=0||today&&!dates.confirmedSource(day,idx.path("source").asText()))throw new IllegalArgumentException();
                    mapper.storeIndex(day,encode(idx));
                    mapper.saveRuntime(errorKey,encode(Map.of("asOf",day,"code","RECOVERED","source",idx.path("source").asText(),"observedAt",OffsetDateTime.now(clock))));
                }catch(Exception unavailable){mapper.saveRuntime(errorKey,encode(Map.of("asOf",day,"code","TREND_INDEX_UNAVAILABLE","retryAfter",OffsetDateTime.now(clock).plusMinutes(15))));}
            }
            for(var item:manualOnly?mapper.pendingManual(day):mapper.pending(day)){
                if(System.nanoTime()-began>45_000_000_000L)break;processed++;
                if(item.name.toUpperCase(Locale.ROOT).contains("ST")){mapper.item(day,item.symbol,"EXCLUDED","ST",null);continue;}
                String stage="RAW_HISTORY";
                try{List<MarketDailyBar> raw=history(item.symbol,day,Adjustment.NONE);stage="QFQ_HISTORY";List<MarketDailyBar> adjusted=history(item.symbol,day,Adjustment.QFQ);
                    var commonStart=raw.getFirst().tradeDate.isAfter(adjusted.getFirst().tradeDate)?raw.getFirst().tradeDate:adjusted.getFirst().tradeDate;
                    raw=raw.stream().filter(b->!b.tradeDate.isBefore(commonStart)).toList();adjusted=adjusted.stream().filter(b->!b.tradeDate.isBefore(commonStart)).toList();
                    if(raw.size()<120||adjusted.size()<120){mapper.item(day,item.symbol,"EXCLUDED","HISTORY_UNDER_120",null);continue;}
                    stage="CALCULATION";var result=client.calculate(item.symbol,convert(adjusted,Adjustment.QFQ),convert(raw,Adjustment.NONE),day.equals(dates.today()));
                    validateMetrics(item.symbol,day,result);preparationWriter.ready(day,item.symbol,encode(result));
                }catch(Exception failed){mapper.item(day,item.symbol,"FAILED","TREND_"+stage+"_FAILED",null);}
            }
            mapper.saveRuntime("last-prepare",encode(Map.of("asOf",day,"processed",processed,"coverage",mapper.coverage(day),"observedAt",OffsetDateTime.now(clock))));return Map.of("asOf",day,"processed",processed,"coverage",mapper.coverage(day));
        }finally{preparing.set(false);}
    }
    private List<MarketDailyBar> history(String symbol,LocalDate day,Adjustment adjustment){var start=day.minusDays(730);var data=bars.daily(symbol,start,day,adjustment);if(data.size()<120||!data.getLast().tradeDate.equals(day)){collection.collect(symbol,start,day,adjustment);data=bars.daily(symbol,start,day,adjustment);}if(data.isEmpty()||!data.getLast().tradeDate.equals(day))throw new IllegalArgumentException("HISTORY_NOT_CURRENT");return data;}
    private static List<Bar> convert(List<MarketDailyBar> rows,Adjustment adjustment){return rows.stream().map(b->new Bar(b.symbol,b.tradeDate,b.openPrice,b.highPrice,b.lowPrice,b.closePrice,b.volume,b.amount,b.turnoverRate,adjustment,b.source,true)).toList();}
    private static void validateMetrics(String symbol,LocalDate day,JsonNode m){if(m==null||!symbol.equals(m.path("symbol").asText())||!day.toString().equals(m.path("as_of").asText())||!"trend-ma-atr-sma-v1".equals(m.path("version").asText())||!m.path("window_hash").asText().matches("[a-f0-9]{64}")||m.path("history_size").asInt()<120)throw new IllegalArgumentException("METRICS_INVALID");for(String f:List.of("close","raw_close","previous_raw_close","ma20","ma60","ma120","raw_factor","recent_twenty_high","previous_twenty_high"))if(TrendRules.n(m,f).signum()<=0)throw new IllegalArgumentException("METRICS_INVALID");if(TrendRules.n(m,"raw_atr14").signum()<0)throw new IllegalArgumentException("METRICS_INVALID");TrendRules.n(m,"ma60_change20");}
    public Object scan(boolean dryRun){if(dryRun)return Map.of("dryRun",true,"action","指数和全市场报价轮询；预告/确认各股每日一次，不调用模型");
        if(!"OPEN".equals(dates.calendarStatus(dates.today())))return Map.of("status","MARKET_CLOSED");var time=LocalTime.now(clock.withZone(TradingDatePolicy.ZONE));boolean close=!time.isBefore(LocalTime.of(15,0));
        if(!close&&!com.stocksentinel.intraday.IntradayValidation.scanTime(time))return Map.of("status","OUTSIDE_SESSION");if(close&&!dates.allowToday(dates.today()))return Map.of("status","CLOSE_CONFIRMATION_REQUIRED");
        if(!scanning.compareAndSet(false,true))return Map.of("status","BUSY");
        int checked=0,failed=0;long began=scanNanos.getAsLong();
        try{var today=dates.today();var prior=calendar.previousOpen(today);if(prior==null)return Map.of("status","CALENDAR_MISSING");var all=mapper.universe();var names=new HashMap<String,String>();var keys=new ArrayList<String>();
            for(var s:all){String key=(s.exchange.equals("SH")?"sh":"sz")+s.symbol;keys.add(key);names.put(s.symbol,s.name.replaceAll("[\\p{Cntrl}<>`*_]"," "));}
            var cursor=node(mapper.runtime("quote-cursor"));String last=cursor==null?"":cursor.path("key").asText();int index=keys.indexOf(last);if(index>=0)Collections.rotate(keys,-(index+1));
            var full=new ArrayList<>(keys);if(!full.contains("sh000300"))full.add("sh000300");
            String phase=close?"CONFIRMED":"PREVIEW";var cycle=coverage.begin(today,phase,full);var covered=coverage.covered(cycle);
            var priority=new LinkedHashSet<String>();priority.add("sh000300");for(var s:mapper.priority())priority.add(("SH".equals(s.exchange)?"sh":"sz")+s.symbol);
            var requested=new ArrayList<>(priority);for(String key:keys)if(!priority.contains(key)&&!covered.contains(key))requested.add(key);
            var market=node(mapper.index(close?today:prior));boolean marketOk=TrendRules.market(market);
            for(int offset=0;offset<requested.size();offset+=100){if(scanNanos.getAsLong()-began>45_000_000_000L)break;var batch=requested.subList(offset,Math.min(offset+100,requested.size()));
                var observations=new ArrayList<com.stocksentinel.monitoring.ScanCoverageMapper.Quote>();
                String nextCursor=null;for(String key:batch)if(!priority.contains(key))nextCursor=key;
                JsonNode response;
                try{response=client.quotes(batch);}catch(Exception unavailable){for(String key:batch)observations.add(new com.stocksentinel.monitoring.ScanCoverageMapper.Quote(key,"FAILED","QUOTE_SOURCE_UNAVAILABLE",false,null));failed+=batch.size();coverage.record(cycle,observations,nextCursor);continue;}
                try{var observed=OffsetDateTime.parse(response.path("observed_at").asText());var now=OffsetDateTime.now(clock);if(!"TENCENT_TREND_QUOTES".equals(response.path("source").asText())||!response.path("quotes").isArray()||observed.isBefore(now.minusSeconds(30))||observed.isAfter(now.plusSeconds(5)))throw new IllegalArgumentException();}
                catch(Exception invalid){for(String key:batch)observations.add(new com.stocksentinel.monitoring.ScanCoverageMapper.Quote(key,"FAILED","QUOTE_ENVELOPE_INVALID",false,null));failed+=batch.size();coverage.record(cycle,observations,nextCursor);continue;}
                var quotes=new HashMap<String,JsonNode>();var duplicates=new HashSet<String>();
                for(var q:response.path("quotes")){String key=q.path("key").asText();if(batch.contains(key)&&quotes.putIfAbsent(key,q)!=null)duplicates.add(key);}
                for(String key:batch){var q=quotes.get(key);var observation=new com.stocksentinel.monitoring.ScanCoverageMapper.Quote(key,"FAILED","QUOTE_MISSING",false,null);observations.add(observation);
                    if(q==null){failed++;continue;}checked++;if(duplicates.contains(key)){observation.code="QUOTE_DUPLICATE";failed++;continue;}
                    try{var at=OffsetDateTime.parse(q.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE);var now=OffsetDateTime.now(clock);
                        if(!at.toLocalDate().equals(today)||at.toInstant().isAfter(now.plusSeconds(5).toInstant())||(!close&&at.toInstant().isBefore(now.minusSeconds(120).toInstant()))||close&&at.toLocalTime().isBefore(LocalTime.of(15,0))){observation.code="QUOTE_STALE_OR_FUTURE";failed++;continue;}
                        observation.code="QUOTE_INVALID";
                        if(!key.substring(2).equals(q.path("symbol").asText())||TrendRules.n(q,"price").signum()<=0){observation.code="QUOTE_IDENTITY_OR_PRICE_INVALID";failed++;continue;}
                        // Keep the existing stock volume gate; the index does not require positive volume.
                        if(!key.equals("sh000300")&&TrendRules.n(q,"volume").signum()<=0){observation.outcome="SKIPPED";observation.code="NO_TRADES";continue;}
                        observation.dataValid=true;observation.quoteAt=at.toOffsetDateTime();observation.outcome="CHECKED";observation.code="RULE_NOT_MATCHED";
                        if(key.equals("sh000300")){observation.code="MARKET_CHECKED";marketNotice(market,q,marketOk,close);continue;}
                        String symbol=key.substring(2);
                        var previous=node(mapper.metrics(symbol,prior));var current=close?node(mapper.metrics(symbol,today)):null;
                        ledger.observe(symbol,q,previous,current);
                        var m=close?current:previous;
                        if(!marketOk){observation.outcome="SKIPPED";observation.code="MARKET_FILTER_BLOCKED";continue;}
                        if(m==null){observation.outcome="SKIPPED";observation.code="HISTORY_NOT_READY";continue;}
                        if(names.getOrDefault(symbol,"").toUpperCase(Locale.ROOT).contains("ST")||q.path("name").asText().toUpperCase(Locale.ROOT).contains("ST")){observation.outcome="SKIPPED";observation.code="ST";continue;}
                        if(previous==null||TrendRules.n(q,"previous_close").setScale(2,RoundingMode.HALF_UP).compareTo(TrendRules.n(previous,"raw_close").setScale(2,RoundingMode.HALF_UP))!=0){observation.outcome="SKIPPED";observation.code="BASIS_MISMATCH";continue;}
                        var price=TrendRules.n(q,"price");if(q.path("limit_up").isNull()||q.path("limit_up").isMissingNode()||TrendRules.n(q,"limit_up").signum()<=0||price.compareTo(TrendRules.n(q,"limit_up"))>=0){observation.outcome="SKIPPED";observation.code="PRICE_LIMIT_UNAVAILABLE_OR_REACHED";continue;}
                        if(close&&price.compareTo(TrendRules.n(m,"raw_close"))!=0){observation.outcome="SKIPPED";observation.code="CLOSE_PRICE_MISMATCH";continue;}
                        BigDecimal amount=close?m.path("amount").isNull()?null:TrendRules.n(m,"amount"):q.path("amount").isNull()?null:TrendRules.n(q,"amount");
                        if(TrendRules.buy(m,price,amount,!close,settings)){observation.code="SIGNAL_MATCHED";var snapshot=Map.of("version","TREND_FOLLOW_V1/1.0.0","metrics",m,"market",market,"quote",q,"phase",phase,"minAmount",settings.minAmount);
                            {String text=MessageIdentity.header(MessageIdentity.Kind.TREND,close?"收盘突破确认":"盘中突破预告","趋势跟踪","公共观察",close?"收盘确认":"盘中预告")+"建议操作："+(close?"下一开市日观察执行；先核对账户风险预算，不假设按今日收盘价买入。":"加入观察，等待收盘确认，不视为正式买入信号。")+"\n股票："+names.get(symbol)+" "+symbol+"\n参考价格："+price+" 元（不复权）\n触发依据：中期上升趋势、突破20日通道，成交额 "+amount+" 元≥"+settings.minAmount+" 元\n日线依据："+m.path("as_of").asText()+"；沪深300收盘过滤依据 "+market.path("as_of").asText()+"\n报价时间："+MessageDisplay.time(at.toOffsetDateTime())+"（北京时间）\n风险：假突破、跳空、涨跌停及费用会影响成交；初始参数未回测，不自动下单。\n实际买入可@助手：记录"+names.get(symbol)+"趋势买入100股，成交价X元";publisher.stock(symbol,today,phase,encode(snapshot),text);}
                        }
                    }catch(Exception invalid){observation.outcome="FAILED";observation.code=observation.dataValid?"TREND_EVALUATION_FAILED":"QUOTE_INVALID";failed++;}
                }
                coverage.record(cycle,observations,nextCursor);
            }
            var cycleResult=coverage.finish(cycle);var result=Map.of("status",checked>=requested.size()&&failed==0?"COMPLETED":"PARTIAL","tradeDate",today,"checked",checked,"requested",full.size(),"scheduled",requested.size(),"quoteCycle",cycleResult,"failed",failed,"observedAt",OffsetDateTime.now(clock),"note","quoteCycle为跨批次轮转；尝试覆盖不等于有效行情或规则命中");mapper.saveRuntime("last-scan",encode(result));return result;
        }finally{scanning.set(false);}
    }
    private void marketNotice(JsonNode basis,JsonNode quote,boolean allowed,boolean close){String direction=basis==null?"DATA_MISSING":TrendRules.n(quote,"price").compareTo(TrendRules.n(basis,"ma200"))>0?"ABOVE":"BELOW_OR_EQUAL";String state=dates.today()+":"+allowed+":"+direction+":"+close;var prior=node(mapper.runtime("market-state"));if(prior!=null&&state.equals(prior.path("state").asText()))return;
        publisher.market(state,encode(Map.of("state",state)),MessageIdentity.header(MessageIdentity.Kind.MARKET,allowed?"大盘趋势过滤通过":basis==null?"大盘数据缺失·暂停新增买入观察":"大盘偏弱·暂停新增买入观察","趋势跟踪·大盘过滤","全市场状态",close?"收盘过滤":"盘中状态")+"沪深300当前指数："+quote.path("price").asText()+"；盘中相对已确认MA200："+(direction.equals("ABOVE")?"上方":direction.equals("DATA_MISSING")?"基准缺失":"下方或相等")+"\n收盘过滤："+(basis==null?"缺少当期有效日线基准":allowed?"高于MA200":"低于或等于MA200")+"，依据 "+(basis==null?"缺失":basis.path("as_of").asText())+"\n指数日线来源："+(basis==null?"无":basis.path("source").asText())+"\n当前指数仅预警，不替代收盘过滤；已有持仓止损继续监控，不自动下单。");}
}
