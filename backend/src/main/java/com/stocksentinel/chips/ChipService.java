package com.stocksentinel.chips;
import com.fasterxml.jackson.databind.*;import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.notification.*;
import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import javax.sql.DataSource;

public class ChipService {
    private final ChipMapper mapper;private final ChipClient client;private final ChipSettings settings;private final TradingDatePolicy dates;
    private final ObjectMapper json;private final Clock clock;private final DataSource source;private final ChipWriter writer;private final int namespace;
    private final AtomicBoolean active=new AtomicBoolean();
    public ChipService(ChipMapper mapper,ChipClient client,ChipSettings settings,TradingDatePolicy dates,ObjectMapper json,Clock clock,DataSource source,ChipWriter writer,int namespace){this.mapper=mapper;this.client=client;this.settings=settings;this.dates=dates;this.json=json;this.clock=clock;this.source=source;this.writer=writer;this.namespace=namespace;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("CHIP_STATE_INVALID");}}
    private ChipSettings.Parameters parameters(ChipMapper.Run run){try{return json.readValue(run.parameters,ChipSettings.Parameters.class);}catch(Exception e){throw new IllegalStateException("CHIP_PARAMETERS_INVALID");}}
    public Object status(LocalDate day){var date=day==null?dates.today():day;var run=mapper.run(date);var out=new LinkedHashMap<String,Object>();out.put("enabled",true);out.put("scheduleEnabled",settings.schedule);out.put("strategy","CHIP_CONCENTRATION_V1");out.put("tradeDate",date);out.put("status",run==null?"NOT_STARTED":run.paused?"SOURCE_BACKOFF":run.status);out.put("parameters",run==null?settings.parameters:parameters(run));out.put("coverage",mapper.coverage(date));out.put("candidates",mapper.candidates(date));out.put("note","日线筹码估算候选，不代表真实主力持仓或买入信号；失败不等于不符合。");return out;}
    public Object items(LocalDate day,int limit,int offset){if(limit<1||limit>100||offset<0||offset>100000)throw new IllegalArgumentException("INVALID_PAGE");return mapper.items(day==null?dates.today():day,limit,offset);}
    public Object scan(LocalDate day,boolean dryRun){
        var date=day==null?dates.today():day;
        if(date.isAfter(dates.today())||!"OPEN".equals(dates.calendarStatus(date)))throw new IllegalArgumentException("CHIP_OPEN_DATE_REQUIRED");
        if(dryRun)return Map.of("dryRun",true,"tradeDate",date,"parameters",settings.parameters,"action","全市场分批筛选筹码集中候选；首次实际扫描冻结当日参数，完成后最多5只入群通知队列，不调用模型");
        if(date.equals(dates.today())&&!dates.confirmedSource(date,"CHIP_EASTMONEY"))return Map.of("status","CHIP_CLOSE_CONFIRMATION_REQUIRED");
        if(!active.compareAndSet(false,true))return Map.of("status","BUSY");
        try(var c=source.getConnection()){
            try(var s=c.createStatement();var rs=s.executeQuery("SELECT pg_try_advisory_lock("+namespace+",8)")){rs.next();if(!rs.getBoolean(1))return Map.of("status","BUSY");}
            try{
                writer.begin(date,encode(settings.parameters));var run=mapper.run(date);
                if(!"RUNNING".equals(run.status)||run.paused)return status(date);
                var parameters=parameters(run);long began=System.nanoTime();int consecutive=0;var startedOn=dates.today();
                for(var stock:mapper.pending(date)){
                    if(System.nanoTime()-began>45_000_000_000L||!startedOn.equals(dates.today()))break;
                    if(stock.name.toUpperCase(Locale.ROOT).contains("ST")){mapper.item(date,stock.symbol,"EXCLUDED","ST",null,null,null,null);continue;}
                    try{
                        var snapshot=client.snapshot(stock.symbol,date,date.equals(dates.today()));String result=ChipRules.evaluate(snapshot,stock.symbol,date,parameters);
                        var observed=OffsetDateTime.parse(snapshot.path("observed_at").asText());var now=OffsetDateTime.now(clock);if(observed.isBefore(now.minusSeconds(30))||observed.isAfter(now.plusSeconds(5)))throw new IllegalArgumentException("CHIP_SNAPSHOT_STALE");
                        String state=result.equals("MATCHED")?"MATCHED":result.equals("RULE_NOT_MATCHED")?"NO_MATCH":"EXCLUDED";
                        mapper.item(date,stock.symbol,state,result,encode(snapshot),ChipRules.n(snapshot,"concentration90"),ChipRules.n(snapshot,"concentration70"),ChipRules.n(snapshot,"average20_amount"));consecutive=0;
                    }catch(Exception e){String code=e instanceof ChipClient.Failure failure?failure.code:"CHIP_VALIDATION_FAILED";
                        if(code.equals("CHIP_HISTORY_UNDER_120")){mapper.item(date,stock.symbol,"EXCLUDED",code,null,null,null,null);consecutive=0;}
                        else{mapper.item(date,stock.symbol,"FAILED",code,null,null,null,null);if(++consecutive>=3){mapper.pause(date,code);break;}}
                    }
                }
                if(mapper.unfinished(date)==0){boolean failed=mapper.coverage(date).stream().anyMatch(row->"FAILED".equals(row.get("status")));writer.complete(date,failed?"PARTIAL":"COMPLETED",render(date));}
                return status(date);
            }finally{try(var s=c.createStatement()){s.execute("SELECT pg_advisory_unlock("+namespace+",8)");}}
        }catch(java.sql.SQLException e){throw new IllegalStateException("CHIP_DATABASE_UNAVAILABLE");}
        finally{active.set(false);}
    }
    static String pct(Object number){return new java.math.BigDecimal(number.toString()).movePointRight(2).setScale(2,java.math.RoundingMode.HALF_UP)+"%";}
    public String render(LocalDate date){
        var rows=mapper.candidates(date);var p=parameters(mapper.run(date));long total=0,failed=0;
        for(var count:mapper.coverage(date)){long n=((Number)count.get("count")).longValue();total+=n;if("FAILED".equals(count.get("status")))failed+=n;}
        var b=new StringBuilder(MessageIdentity.header(MessageIdentity.Kind.CANDIDATE,"🧩 筹码集中候选","筹码集中度","沪深A股·最多5只","收盘估算"));
        b.append("建议操作：加入研究观察，结合趋势和风险核对，不作为直接买入依据。\n日线依据：").append(date).append("｜股票池 ").append(total).append("｜失败 ").append(failed).append("\n条件：90%集中度≤").append(pct(p.concentration90())).append("；70%≤").append(pct(p.concentration70())).append("；20日均成交额≥").append(p.minimumAmount()).append("元。\n");
        int i=0;for(var row:rows){try{var m=json.readTree((String)row.get("snapshot"));b.append(++i).append(". ").append(String.valueOf(row.get("name")).replaceAll("[\\p{Cntrl}<>`*_]"," ")).append(' ').append(row.get("symbol")).append("\n90%集中度 ").append(pct(m.path("concentration90").asText())).append("｜70% ").append(pct(m.path("concentration70").asText())).append("\n90%成本区间 ").append(m.path("low90").asText()).append('～').append(m.path("high90").asText()).append("元；70%区间 ").append(m.path("low70").asText()).append('～').append(m.path("high70").asText()).append("元\n估算成本 ").append(m.path("average_cost").asText()).append("元｜获利比例 ").append(pct(m.path("profit_ratio").asText())).append('\n');}catch(Exception e){throw new IllegalStateException("CHIP_SNAPSHOT_INVALID");}}
        if(rows.isEmpty())b.append(failed>0?"有效覆盖中暂无候选，存在采集失败，不能据此认定全市场没有符合股票。\n":"当前股票池无符合条件候选。\n");
        return b.append("按90%集中度升序、70%升序、成交额降序排序，同值按代码。\n来源：东方财富日线＋AKShare筹码估算；筹码集中不代表主力吸筹，未回测，不自动关注或下单。").toString();
    }
}
