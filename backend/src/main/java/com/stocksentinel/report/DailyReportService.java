package com.stocksentinel.report;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.notification.*;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class DailyReportService {
    private final ReportMapper mapper;private final WatchlistMapper watches;private final NotificationSettings settings;
    private final ObjectProvider<NotificationClient> clients;private final TradingDatePolicy dates;private final ObjectMapper json;
    private final boolean enabled;
    public DailyReportService(ReportMapper mapper,WatchlistMapper watches,NotificationSettings settings,ObjectProvider<NotificationClient> clients,
            TradingDatePolicy dates,ObjectMapper json,@Value("${reports.enabled:false}") boolean enabled) {
        this.mapper=mapper;this.watches=watches;this.settings=settings;this.clients=clients;this.dates=dates;this.json=json;this.enabled=enabled;
    }
    public String render(LocalDate date) {
        var lines=new ArrayList<String>();lines.add("【StockSentinel 日报】 "+date);
        var run=mapper.run(date);var quotes=new HashMap<String,JsonNode>();
        if(run!=null && run.overview!=null) {
            try {var overview=json.readTree(run.overview);for(var q:overview.path("quotes")) quotes.put(q.path("key").asText(),q);
                lines.add("行情快照："+clean(overview.path("observed_at").asText(),32));
            } catch(Exception invalid) {quotes.clear();}
        }
        lines.add("**大盘概况**");
        for(var entry:Map.of("sh000001","上证指数","sz399001","深证成指","sz399006","创业板指").entrySet())
            lines.add(entry.getValue()+"："+quote(quotes.get(entry.getKey()),date));
        int up=0,down=0,flat=0,valid=0;
        for(var stock:mapper.universe()) {var q=quotes.get(("SH".equals(stock.exchange)?"sh":"sz")+stock.symbol);
            if(!validQuote(q,date)) continue;valid++;int cmp=number(q,"price").compareTo(number(q,"previous_close"));if(cmp>0)up++;else if(cmp<0)down++;else flat++;}
        lines.add("有效个股报价 "+valid+"；涨 "+up+" / 跌 "+down+" / 平 "+flat+"（缺失/无成交不计）");
        lines.add("**关注股**");int shown=0,total=0;
        for(var watched:watches.list()) {if(!Boolean.TRUE.equals(watched.enabled)) continue;total++;if(shown>=8)continue;
            var stock=watches.stock(watched.symbol);if(stock==null)continue;
            lines.add(clean(stock.name,18)+" "+stock.symbol+"："+quote(quotes.get(("SH".equals(stock.exchange)?"sh":"sz")+stock.symbol),date));shown++;}
        if(total==0)lines.add("暂无启用关注股");if(total>shown)lines.add("其余 "+(total-shown)+" 只详见关注名单 API");
        lines.add("**当日规则与运行**");
        var signals=mapper.signals(date);lines.add(signals.isEmpty()?"未记录规则信号（不代表无风险）":summary(signals,"signal_type"));
        var jobs=mapper.jobs(date);lines.add(jobs.isEmpty()?"未记录扫描任务，无法证明当日监控正常": "扫描任务："+summary(jobs,"status"));
        lines.add("**技术关注候选（最多5只）**");
        if(run==null)lines.add("尚无全市场扫描记录，候选数据不足");
        else {
            lines.add("日线依据 "+run.asOf+"；扫描状态 "+run.status+"；股票池 "+run.total+"；"+summary(mapper.coverage(date),"status"));
            int count=0;
            for(var c:mapper.candidates(date)) {
                var stock=watches.stock(c.get("symbol").toString());if(stock==null)continue;
                var q=quotes.get(("SH".equals(stock.exchange)?"sh":"sz")+stock.symbol);
                if(!validQuote(q,date) || OffsetDateTime.parse(q.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE).toLocalTime().isBefore(LocalTime.of(15,0))
                        || q.path("name").asText().toUpperCase(Locale.ROOT).contains("ST"))continue;
                lines.add((++count)+". "+clean(q.path("name").asText(),18)+" "+stock.symbol+"：MACD金叉、价在MA20上方且MA20向上、量能满足策略阈值；20日均额≥1亿。");
                Object explanation=c.get("explanation");lines.add(explanation==null ? "模型解释未生成；仅展示规则依据。" : "模型解释："+clean(explanation.toString(),80));
            }
            if(count==0)lines.add("暂无经数据与收盘报价复核的候选；不强行推荐。");
        }
        lines.add("候选按20日均成交额降序，同值按代码排序；覆盖不足不代表全市场结论。未人工确认当日日线时使用上一开市日，日期见上。技术候选不代表基本面优质，不自动加关注或下单。");
        String text=String.join("\n",lines);
        if(text.getBytes(StandardCharsets.UTF_8).length<=3900)return text;
        var out=new StringBuilder();for(int cp:text.codePoints().toArray()) {String next=new String(Character.toChars(cp));if((out.toString()+next).getBytes(StandardCharsets.UTF_8).length>3700)break;out.append(next);}
        return out+"\n…内容已截短，详见本机日报及筛选 API；仅供关注，不自动下单。";
    }
    private static String summary(List<Map<String,Object>> rows,String key) {return rows.isEmpty()?"暂无统计":rows.stream().map(r->r.get(key)+"="+r.get("count")).collect(java.util.stream.Collectors.joining("，"));}
    private static BigDecimal number(JsonNode q,String field){return new BigDecimal(q.path(field).asText());}
    private static boolean validQuote(JsonNode q,LocalDate date) {try {return q!=null && OffsetDateTime.parse(q.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate().equals(date)
        && number(q,"price").signum()>0 && number(q,"previous_close").signum()>0 && number(q,"volume").signum()>0;}catch(Exception invalid){return false;}}
    private static String quote(JsonNode q,LocalDate date) {if(!validQuote(q,date))return "当日报价缺失/无成交";
        var pct=number(q,"price").subtract(number(q,"previous_close")).multiply(BigDecimal.valueOf(100)).divide(number(q,"previous_close"),2,RoundingMode.HALF_UP);
        return number(q,"price").toPlainString()+"（"+(pct.signum()>0?"+":"")+pct.toPlainString()+"%），"+OffsetDateTime.parse(q.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE).toLocalTime();}
    static String clean(String s,int max) {s=s.replaceAll("[\\r\\n\\p{Cntrl}<>`*]"," ");return s.codePointCount(0,s.length())<=max?s:s.substring(0,s.offsetByCodePoints(0,max))+"…";}
    public String destinationHash() {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((settings.botId+"\n"+settings.chatId).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Report key unavailable");}}
    public ReportMapper.Delivery enqueue(LocalDate date) {
        if(!enabled || !settings.enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DAILY_REPORT_DISABLED");
        if(!date.equals(dates.today()) || !dates.afterClose() || !"OPEN".equals(dates.calendarStatus(date)))throw new ResponseStatusException(HttpStatus.CONFLICT,"CURRENT_CLOSED_TRADING_DAY_REQUIRED");
        String hash=destinationHash();var existing=mapper.report(date,hash);if(existing!=null)return existing;
        mapper.enqueue(date,hash,render(date));return mapper.report(date,hash);
    }
    public boolean deliverOne() {
        if(!enabled || !settings.enabled)return false;var client=clients.getIfAvailable();if(client==null)return false;
        mapper.expire();var row=mapper.claim(destinationHash(),UUID.randomUUID().toString());if(row==null)return false;
        NotificationClient.Delivery delivery;
        try {delivery=client.send(settings.chatId,row.payload);}catch(Exception unconfirmed){delivery=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED");}
        String status=switch(delivery.outcome()){case SUCCESS->"SUCCESS";case UNKNOWN->"UNKNOWN";case REJECTED->"FAILED";case RETRY->row.attempts>=3?"FAILED":"RETRY";};
        mapper.delivered(row.id,row.claimToken,status,"SUCCESS".equals(status)?null:"DELIVERY_"+delivery.outcome().name());return true;
    }
}
