package com.stocksentinel.document;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.report.ReportMapper;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.pullback.PullbackSettings;
import com.stocksentinel.notification.MessageDisplay;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;

/** Public, local-only daily facts. Personal tables and external clients are deliberately absent. */
public class StockDocumentSnapshot {
    public static final List<String> FIELDS=List.of("记录键","交易日","股票名称","股票代码","来源类别","建议操作","参考价格","规则依据","风险提示","信号时间","日线依据","数据覆盖");
    private final DocumentArchiveMapper archives;private final ReportMapper reports;private final WatchlistMapper watches;
    private final PullbackSettings pullback;private final ObjectMapper json;
    public StockDocumentSnapshot(DocumentArchiveMapper archives,ReportMapper reports,WatchlistMapper watches,PullbackSettings pullback,ObjectMapper json){this.archives=archives;this.reports=reports;this.watches=watches;this.pullback=pullback;this.json=json;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> build(LocalDate date) {
        var run=reports.run(date);var rows=new TreeMap<String,Map<String,String>>();var categories=new HashMap<String,LinkedHashSet<String>>();
        for(var w:watches.list())if(Boolean.TRUE.equals(w.enabled)){var s=watches.stock(w.symbol);if(s!=null)row(rows,categories,s.symbol,s.name,"公开关注");}
        for(var c:reports.candidates(date)) {
            var r=row(rows,categories,value(c,"symbol"),value(c,"name"),"技术候选");
            r.put("规则依据","已通过现有日线技术筛选；最多5只，按20日均成交额排序。");
            r.put("建议操作","技术关注观察，不等于买入指令。");
        }
        if(pullback.enabled()&&run!=null&&run.asOf!=null)for(var c:reports.pullbacks(run.asOf,pullback.version())) {
            var r=row(rows,categories,value(c,"symbol"),value(c,"name"),"缩量回踩候选");
            r.merge("规则依据","缩量回踩入池；突破观察价 "+value(c,"reference_high")+" 元（不复权）；待完整分钟突破及量能确认。",(a,b)->a+"；"+b);
            r.putIfAbsent("建议操作","等待盘中转强确认，尚不等于触发买入。");
        }
        for(var s:archives.publicSignals(date)) {
            var r=row(rows,categories,value(s,"symbol"),value(s,"name"),"公共信号");
            r.merge("规则依据","当日最近信号："+label(value(s,"signal_type"))+"；"+value(s,"reason"),(a,b)->a+"；"+b);
            r.put("参考价格",value(s,"reference_price")+" 元（不复权信号价，非实时价）");r.put("信号时间",time(s.get("signal_at"),date));
            r.put("建议操作","仅观察本地"+label(value(s,"signal_type"))+"，未生成模型建议。");
            String risk=value(s,"risk_note");
            try {var a=json.readTree(value(s,"advice"));if(a.path("suggestion").isTextual())r.put("建议操作","AI辅助："+a.path("suggestion").asText());
                if(a.path("risks").isArray()){var risks=new ArrayList<String>();for(var item:a.path("risks"))if(item.isTextual())risks.add(item.asText());risk+="；"+String.join("；",risks);}
                if(a.path("invalidation").isTextual())risk+="；失效条件："+a.path("invalidation").asText();
            }catch(Exception invalid){/* Missing model output never creates a recommendation. */}
            r.put("风险提示",risk);
        }
        String coverage;
        if(run==null)coverage="全市场扫描尚未开始；不能代表完整市场结论。";
        else {var counts=reports.coverage(date);long pending=counts.stream().filter(c->"PENDING".equals(value(c,"status"))).mapToLong(c->((Number)c.get("count")).longValue()).sum();
            long failed=counts.stream().filter(c->"FAILED".equals(value(c,"status"))).mapToLong(c->((Number)c.get("count")).longValue()).sum();
            coverage="扫描状态 "+run.status+"；已处理 "+(run.total-pending)+"/"+run.total+"，待处理 "+pending+"，失败 "+failed+"；仅为冻结时点覆盖，不能代表未扫描或计算失败的股票。";}
        var records=new ArrayList<Map<String,Object>>();
        for(var entry:rows.entrySet()) {
            var r=entry.getValue();r.put("记录键",date+":"+entry.getKey());r.put("交易日",date.toString());r.put("来源类别",String.join("、",categories.get(entry.getKey())));
            r.putIfAbsent("建议操作","公开关注观察，未产生可引用的当日规则建议。");r.putIfAbsent("参考价格","无当日公共信号价，未另取行情");
            r.putIfAbsent("规则依据","仅为关注列表，不代表已满足买卖条件。");r.putIfAbsent("信号时间","无当日公共信号");
            r.put("日线依据",run==null||run.asOf==null?"选股日线尚未确认":"选股/回踩依据 "+run.asOf+"；公共信号以信号时间为准");r.put("数据覆盖",coverage);
            r.merge("风险提示","仅供技术观察；非实时行情，规则未经收益验证；不自动关注或下单。",(a,b)->a+"；"+b);
            var values=new LinkedHashMap<String,Object>();for(String field:FIELDS)values.put(field,List.of(Map.of("type","text","text",r.get(field))));
            records.add(Map.of("values",values));
        }
        return records;
    }
    private static Map<String,String> row(Map<String,Map<String,String>> rows,Map<String,LinkedHashSet<String>> categories,String symbol,String name,String category) {
        if(!symbol.matches("[0-9]{6}"))throw new IllegalStateException("DOCUMENT_STOCK_INVALID");
        categories.computeIfAbsent(symbol,k->new LinkedHashSet<>()).add(category);
        return rows.computeIfAbsent(symbol,k->{var r=new LinkedHashMap<String,String>();r.put("股票名称",name);r.put("股票代码",symbol);return r;});
    }
    private static String value(Map<String,Object> row,String key){var v=row.get(key);return v==null?"未记录":v.toString();}
    private static String time(Object value,LocalDate date) {
        if(value instanceof OffsetDateTime at)return MessageDisplay.time(at)+"（北京时间）";
        if(value instanceof java.sql.Timestamp at)return MessageDisplay.time(at.toInstant().atOffset(ZoneOffset.UTC))+"（北京时间）";
        if(value==null)return date+" 收盘（日线）";
        return "时间未能确认";
    }
    private static String label(String type){return switch(type){case "BUY_WATCH"->"买入观察";case "SELL_WATCH"->"卖出观察";case "RISK_ALERT"->"风险预警";case "RAPID_RISE"->"快速拉升";case "RAPID_DROP"->"快速跳水";case "BREAKOUT_WATCH"->"突破观察";case "PULLBACK_STRENGTH"->"缩量回踩转强";case "TAKE_PROFIT"->"止盈价位";case "STOP_LOSS"->"止损价位";default->"规则观察";};}
}
