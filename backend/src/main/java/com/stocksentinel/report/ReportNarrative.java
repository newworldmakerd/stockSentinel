package com.stocksentinel.report;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.common.entity.StockInfo;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Fact-derived narrative only; thresholds and stock eligibility remain in the existing rule engine. */
final class ReportNarrative {
    private static final List<String> INDEX_KEYS=List.of("sh000001","sz399001","sz399006");
    private static final List<String> INDEX_NAMES=List.of("上证","深成指","创业板");
    private static final Map<String,String> RULE_NAMES=Map.of("MACD_GOLDEN_CROSS","MACD金叉","CLOSE_ABOVE_MA20","收盘站上MA20","MA20_RISING","MA20向上","VOLUME_CONFIRMATION","放量确认");
    private final ReportMapper mapper;private final WatchlistMapper watches;private final ObjectMapper json;
    private final com.stocksentinel.pullback.PullbackSettings pullback;
    ReportNarrative(ReportMapper mapper,WatchlistMapper watches,ObjectMapper json){this(mapper,watches,json,null);}
    ReportNarrative(ReportMapper mapper,WatchlistMapper watches,ObjectMapper json,com.stocksentinel.pullback.PullbackSettings pullback){this.mapper=mapper;this.watches=watches;this.json=json;this.pullback=pullback;}
    String render(LocalDate date) {
        var lines=new ArrayList<String>();lines.add(com.stocksentinel.notification.MessageIdentity.header(com.stocksentinel.notification.MessageIdentity.Kind.REPORT,"收盘日报","日线技术选股与行情汇总","公开汇总","交易日快照")+"📅 "+date);
        var run=mapper.run(date);var quotes=new HashMap<String,JsonNode>();
        if(run!=null && run.overview!=null)try {for(var q:json.readTree(run.overview).path("quotes"))quotes.put(q.path("key").asText(),q);}catch(Exception invalid){quotes.clear();}
        var universe=mapper.universe();int up=0,down=0,flat=0;
        for(var stock:universe){var q=quotes.get(key(stock));if(!valid(q,date))continue;int direction=num(q,"price").compareTo(num(q,"previous_close"));if(direction>0)up++;else if(direction<0)down++;else flat++;}
        int valid=up+down+flat;
        lines.add("🌐 **大盘概览**");
        var indexMoves=new ArrayList<BigDecimal>();for(var k:INDEX_KEYS)if(valid(quotes.get(k),date))indexMoves.add(pct(quotes.get(k)));
        if(valid==0)lines.add("当日市场报价不足，暂不判断市场方向。");
        else {
            String breadth=up>down?"个股涨多跌少":up<down?"个股跌多涨少":"涨跌家数接近";
            String indices=indexMoves.size()!=3?"指数数据未齐":indexMoves.stream().allMatch(p->p.signum()>0)?"三大指数均上涨":indexMoves.stream().allMatch(p->p.signum()<0)?"三大指数均下跌":"三大指数表现分化";
            lines.add(breadth+"，"+indices+"。有效报价中上涨占比 "+BigDecimal.valueOf(up*100L).divide(BigDecimal.valueOf(valid),1,RoundingMode.HALF_UP)+"%。");
            lines.add("上涨 "+up+" / 下跌 "+down+" / 平盘 "+flat+"；报价覆盖 "+valid+"/"+universe.size()+" 只。");
        }
        lines.add("| 指数 | 点位 · 涨跌 · 报价时间 |\n| :--- | :--- |");
        for(int i=0;i<INDEX_KEYS.size();i++){var q=quotes.get(INDEX_KEYS.get(i));lines.add("| "+INDEX_NAMES.get(i)+" | "+quote(q,date)+" |");}
        lines.add("\n⭐ **关注股**");lines.add("| 股票 | 现价 · 涨跌 · 时间 |\n| :--- | :--- |");int shown=0,total=0;var watchNotes=new ArrayList<String>();
        for(var w:watches.list()){
            if(!Boolean.TRUE.equals(w.enabled))continue;total++;if(shown>=6)continue;var stock=watches.stock(w.symbol);if(stock==null)continue;shown++;
            var q=quotes.get(key(stock));String text="| "+clean(stock.name,18)+" "+stock.symbol+" | "+quote(q,date)+" |";
            var sh=quotes.get("sh000001");if(valid(q,date)&&valid(sh,date)){var relative=pct(q).subtract(pct(sh));watchNotes.add("> "+clean(stock.name,18)+"：较沪指"+(relative.signum()>=0?"强 ":"弱 ")+relative.abs().setScale(2,RoundingMode.HALF_UP)+" 个百分点");}
            lines.add(text);
            var detail=details(mapper.stockDetails(date,stock.symbol));
            if(detail==null)watchNotes.add("> "+clean(stock.name,18)+"：技术历史尚未齐，暂不判断均线与MACD状态。");
            else {watchNotes.add("> "+evidence(detail));watchNotes.add("> "+observation(detail));}
        }
        lines.addAll(watchNotes);
        if(total==0)lines.add("暂无启用关注股。");if(total>shown)lines.add("其余 "+(total-shown)+" 只详见关注名单。");
        lines.add("\n🎯 **下一交易日技术候选**");lines.add("| 股票 | 量比 | 20日均额 |\n| :--- | :--- | :--- |");int matched=0;var candidateNotes=new ArrayList<String>();
        if(run!=null)for(var c:mapper.candidates(date)){
            var stock=watches.stock(c.get("symbol").toString());if(stock==null)continue;var q=quotes.get(key(stock));
            if(!valid(q,date)||OffsetDateTime.parse(q.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE).toLocalTime().isBefore(LocalTime.of(15,0))||q.path("name").asText().toUpperCase(Locale.ROOT).contains("ST"))continue;
            var d=details(c.get("details")==null?null:c.get("details").toString());
            lines.add("| "+(++matched)+". "+clean(stock.name,18)+" "+stock.symbol+" | "+volumeRatio(d)+" | "+averageAmount(c)+" |");
            if(d!=null)candidateNotes.add("> "+evidence(d));
            var explanation=c.get("explanation");candidateNotes.add(explanation==null?"> 模型解释未生成，仅展示规则依据。":"> 🤖 "+clean(stock.name,18)+"：模型解释："+clean(explanation.toString(),50));
        }
        lines.addAll(candidateNotes);
        if(matched>0)lines.add("候选已满足MACD金叉、站上MA20、MA20向上及放量条件。观察失效：收盘回到MA20下方或量能不再成立时重新评估。");
        long processed=0,waiting=0,failed=0,qualified=0;
        if(run!=null)for(var row:mapper.coverage(date)){long count=((Number)row.get("count")).longValue();String status=row.get("status").toString();if("PENDING".equals(status))waiting+=count;else processed+=count;if("FAILED".equals(status))failed+=count;if("CANDIDATE".equals(status))qualified+=count;}
        if(matched==0){
            if(run!=null&&"COMPLETED".equals(run.status)&&waiting==0&&failed==0&&qualified==0)lines.add("已完成本次筛选，没有股票同时满足4项技术条件；维持原标准。");
            else if(qualified>0)lines.add("规则候选尚缺当日收盘报价复核，暂不列入推荐。");
            else lines.add("选股数据尚未齐，暂不能给出可靠的全市场候选名单。");
        }
        if(pullback!=null && pullback.enabled() && run!=null) {
            lines.add("\n🔎 **缩量回踩观察池**");
            var pool=mapper.pullbacks(run.asOf,pullback.version());
            if(pool.isEmpty())lines.add("当前已处理范围内暂无合格回踩候选，数据未齐时不代表全市场没有机会。");
            if(!pool.isEmpty())lines.add("| 股票 | 突破观察价（元） |\n| :--- | :--- |");
            for(var c:pool)lines.add("| "+clean(c.get("name").toString(),18)+" "+c.get("symbol")+" | "+c.get("reference_high")+" |");
            if(!pool.isEmpty())lines.add("日线依据 "+run.asOf+"；待下一交易日完整分钟收盘突破观察价且量能≥"+pullback.minuteMultiplier()+"倍，才发转强提醒。");
        }
        int footerStart=lines.size();lines.add("\n🛠 **运行与数据依据**");
        var jobs=mapper.jobs(date);long scans=0,errors=0;for(var row:jobs){String s=row.get("status").toString();long n=((Number)row.get("count")).longValue();if("SUCCESS".equals(s)||"PARTIAL".equals(s))scans+=n;if("FAILED".equals(s)||"PARTIAL".equals(s))errors+=n;}
        if(scans==0)lines.add("没有有效交易时段扫描记录；未收到提醒不代表没有机会或风险。");
        else lines.add("有效扫描 "+scans+" 次"+(errors>0?"；异常任务 "+errors+" 次（含失败任务）":"")+"。");
        var signals=mapper.signals(date);if(!signals.isEmpty())lines.add("规则提醒："+signals.stream().map(r->signalName(r.get("signal_type").toString())+" "+r.get("count")).collect(java.util.stream.Collectors.joining("，")));
        if(run==null)lines.add("全市场技术扫描尚未开始。");else {
            lines.add("选股已处理 "+processed+"/"+run.total+" 只，待处理 "+waiting+" 只，数据/计算失败 "+failed+" 只。");
            lines.add("技术依据截至 "+run.asOf+"；涨跌为 "+date+" 当日报价。"+(failed>0||waiting>0?"覆盖不完整，不能视作全市场结论。":""));
        }
        lines.add("💡 指标详情可@助手查询选股原因。\n⚠️ 候选按20日均成交额排序，仅作技术观察；不代表基本面优质，不自动关注或下单。");
        String text=String.join("\n",lines);if(bytes(text)<=3900)return text;
        // Optional indicator/model commentary yields to complete tables and mandatory data provenance.
        var footer=new ArrayList<>(lines.subList(footerStart,lines.size()));
        var body=new ArrayList<>(lines.subList(0,footerStart));body.removeIf(line->line.startsWith("> "));
        String tail=String.join("\n",footer);text=String.join("\n",body)+"\n"+tail;if(bytes(text)<=3900)return text;
        var out=new StringBuilder();String notice="\n…内容缩略，更多指标请@助手查询。\n";
        for(String line:body){if(bytes(out+line+notice+tail)>3900)break;out.append(line).append('\n');}
        return out+notice+tail;
    }
    private static int bytes(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private String volumeRatio(JsonNode d){try{var c=d.path("context");var prior=new BigDecimal(c.path("previousFiveVolumeSum").asText());return prior.signum()>0?new BigDecimal(c.at("/currentBar/volume").asText()).multiply(BigDecimal.valueOf(5)).divide(prior,2,RoundingMode.HALF_UP)+"倍":"待核验";}catch(Exception missing){return "待核验";}}
    private String averageAmount(Map<String,Object> c){try{return new BigDecimal(c.get("average_amount").toString()).divide(new BigDecimal("100000000"),2,RoundingMode.HALF_UP)+"亿";}catch(Exception missing){return "待核验";}}
    private JsonNode details(String raw){try {return raw==null?null:json.readTree(raw);}catch(Exception invalid){return null;}}
    private String evidence(JsonNode d){
        var c=d.path("context");var ci=c.path("current");var pi=c.path("previous");
        String prefix="依据 "+d.path("asOf").asText()+"（前复权指标）：";
        try {
            var amount=new BigDecimal(d.path("average20Amount").asText()).divide(new BigDecimal("100000000"),2,RoundingMode.HALF_UP);
            var prior=new BigDecimal(c.path("previousFiveVolumeSum").asText());var volume=new BigDecimal(c.at("/currentBar/volume").asText());
            String ratio=prior.signum()>0?volume.multiply(BigDecimal.valueOf(5)).divide(prior,2,RoundingMode.HALF_UP).toPlainString():"无基准";
            return prefix+"MA20 "+ci.path("ma20").asText()+"（前值 "+pi.path("ma20").asText()+"）；量能 "+ratio+" 倍（门槛 "+c.at("/parameters/volumeMultiplier").asText()+"）；20日均成交额 "+amount+" 亿元。";
        }catch(Exception invalid){return prefix+"指标数据不完整。";}
    }
    private String observation(JsonNode d){var missing=new ArrayList<String>();for(var r:d.path("rules"))if(!r.path("passed").asBoolean())missing.add(RULE_NAMES.getOrDefault(r.path("ruleCode").asText(),"规则条件"));
        return missing.isEmpty()?"4项买入观察条件已满足，后续复核是否持续；不等于买入指令。":"尚待确认："+String.join("、",missing)+"；单日涨幅不能替代这些条件。";}
    private static String key(StockInfo s){return ("SH".equals(s.exchange)?"sh":"sz")+s.symbol;}
    private static BigDecimal num(JsonNode q,String field){return new BigDecimal(q.path(field).asText());}
    private static boolean valid(JsonNode q,LocalDate date){try{return q!=null&&OffsetDateTime.parse(q.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate().equals(date)&&num(q,"price").signum()>0&&num(q,"previous_close").signum()>0&&num(q,"volume").signum()>0;}catch(Exception invalid){return false;}}
    private static BigDecimal pct(JsonNode q){return num(q,"price").subtract(num(q,"previous_close")).multiply(BigDecimal.valueOf(100)).divide(num(q,"previous_close"),6,RoundingMode.HALF_UP);}
    private static String quote(JsonNode q,LocalDate date){if(!valid(q,date))return "当日报价缺失/无成交";var p=pct(q).setScale(2,RoundingMode.HALF_UP);return (p.signum()>0?"🔴 ":p.signum()<0?"🟢 ":"⚪ ")+num(q,"price").toPlainString()+"（"+(p.signum()>0?"+":"")+p+"%），"+OffsetDateTime.parse(q.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE).format(DateTimeFormatter.ofPattern("HH:mm"));}
    private static String clean(String s,int max){return DailyReportService.clean(s,max).replace("|","／");}
    private static String signalName(String type){return switch(type){case "PULLBACK_STRENGTH"->"缩量回踩转强";case "BUY_WATCH"->"买入观察";case "SELL_WATCH"->"卖出观察";case "RISK_ALERT"->"趋势风险";case "RAPID_RISE"->"快速上涨";case "RAPID_DROP"->"快速下跌";case "BREAKOUT_WATCH"->"突破放量";case "TAKE_PROFIT"->"止盈价位";case "STOP_LOSS"->"止损价位";default->"其他提醒";};}
}
