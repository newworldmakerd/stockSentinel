package com.stocksentinel.personal;

import com.stocksentinel.stock.*;
import com.stocksentinel.signal.SignalMapper;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.notification.NotificationSettings;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.time.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionService {
    private final PositionMapper mapper;private final WatchlistMapper stocks;private final SignalMapper signals;
    private final NotificationSettings destinations;private final TradingDatePolicy dates;
    private static final Set<String> BUY=Set.of("BUY_WATCH","BREAKOUT_WATCH","PULLBACK_STRENGTH");
    private static final Set<String> SELL=Set.of("SELL_WATCH","RISK_ALERT","STOP_LOSS","TAKE_PROFIT","RAPID_DROP");
    public PositionService(PositionMapper mapper,WatchlistMapper stocks,SignalMapper signals,NotificationSettings destinations,TradingDatePolicy dates){this.mapper=mapper;this.stocks=stocks;this.signals=signals;this.destinations=destinations;this.dates=dates;}
    private void identity(String group,String user){if(!destinations.destinationHashes().contains(group)||user==null||user.isBlank()||user.length()>256||user.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("仓位身份无效");}
    @Transactional public Map<String,Object> set(String group,String user,String symbol,long quantity,BigDecimal cost,Long sellable){
        identity(group,user);WatchlistService.symbol(symbol);var stock=stocks.stock(symbol);
        if(stock==null||!Set.of("SH","SZ").contains(stock.exchange))throw new IllegalArgumentException("仅支持本地目录中的沪深股票");
        if(quantity<0||quantity>1000000000L||(quantity>0&&(cost==null||cost.signum()<=0||cost.scale()>4||cost.precision()-cost.scale()>14))
                ||(quantity==0&&cost!=null)||(sellable!=null&&(sellable<0||sellable>quantity)))throw new IllegalArgumentException("持股、成本或可卖数量无效；可卖不能超过持股，数量单位为股");
        var row=new PositionMapper.Position();row.groupHash=group;row.userId=user;row.symbol=symbol;row.quantity=quantity;row.costPrice=cost;
        row.sellableQuantity=quantity==0?Long.valueOf(0):sellable;row.reportedOn=dates.today();mapper.save(row);return view(mapper.get(group,user,symbol));
    }
    public List<Map<String,Object>> mine(String group,String user,String symbol,int offset){identity(group,user);if(offset<0||offset>10000)throw new IllegalArgumentException("分页无效");if(symbol!=null){var row=mapper.get(group,user,symbol);return row==null?List.of():List.of(view(row));}return mapper.mine(group,user,offset).stream().map(this::view).toList();}
    private Map<String,Object> view(PositionMapper.Position row){var out=new LinkedHashMap<String,Object>();var stock=stocks.stock(row.symbol);
        out.put("symbol",row.symbol);out.put("name",stock==null?"名称未记录":stock.name);out.put("quantity",row.quantity);out.put("costPrice",row.costPrice);
        out.put("sellableQuantity",row.reportedOn.equals(dates.today())?row.sellableQuantity:null);out.put("reportedOn",row.reportedOn);out.put("updatedAt",row.updatedAt);out.put("revision",row.revision);
        out.put("note","用户手工申报；可卖数量仅申报当日有效，未知不猜测，不自动推算T+1；不自动增删关注或执行交易");return out;}
    public Object advice(String group,String user,String symbol){identity(group,user);var row=mapper.get(group,user,symbol);if(row==null)return Map.of("status","POSITION_NOT_REPORTED","symbol",symbol);
        var recent=signals.list(symbol,dates.today().minusDays(5),dates.today(),null,5,0);return Map.of("position",view(row),"signals",recent.stream().map(s->Map.of("signalId",s.id,"tradeDate",s.tradeDate,"signalType",s.signalType,"referencePrice",s.referencePrice,"positionNote",note(row,s,dates.today()),"costComparison",costComparison(row,s))).toList(),"note","最近5个自然日的已落库信号；没有信号不另造买卖建议。仓位未知资金总额，无法计算仓位比例或买入额度");}
    public static String note(PositionMapper.Position p,SignalEvent s,LocalDate today){
        if(p.quantity==0)return BUY.contains(s.signalType)?"已申报空仓：仅作为建仓观察，缺少资金与目标仓位，不能推算买入股数。":"已申报空仓：无现有持股可减仓，不视为卖出指令。";
        if(BUY.contains(s.signalType))return "已申报持仓"+p.quantity+"股：该信号不等于需要追加买入；结合现有持仓控制重复追买，未提供总资金和目标比例，不给出加仓数量。";
        if(SELL.contains(s.signalType)){
            if(p.sellableQuantity==null||(!p.reportedOn.equals(s.tradeDate)||!p.reportedOn.equals(today)))return "已申报持仓"+p.quantity+"股：可卖数量未知或申报已跨日，需先确认实际可卖；不能将全部持仓当作可卖。";
            return p.sellableQuantity==0?"已申报持仓"+p.quantity+"股，当日申报可卖0股：当前没有已确认可卖数量，不建议执行卖出；不自动推算T+1。":"已申报持仓"+p.quantity+"股，当日申报可卖"+p.sellableQuantity+"股：可结合本次规则考虑减仓；减仓量不得超过已申报可卖数量，这不是必须卖出全部的建议。";
        }
        return "本信号不是独立买卖指令；结合已申报持仓观察。";
    }
    public static String costComparison(PositionMapper.Position p,SignalEvent s){
        if(p.quantity==0||p.costPrice==null||s.referencePrice==null)return "无可比较的持仓成本";
        var difference=s.referencePrice.subtract(p.costPrice).multiply(new BigDecimal("100")).divide(p.costPrice,2,java.math.RoundingMode.HALF_UP);
        return "信号参考价较申报成本"+(difference.signum()>0?"+":"")+difference+"%；仅价格差，非实时收益，未计费用或分红送转成本修订";
    }
    /** Called inside the signal notification transaction; no model call per holder. */
    public void enqueue(long id){if(!destinations.enabled)return;var s=signals.find(id);if(s==null||(!BUY.contains(s.signalType)&&!SELL.contains(s.signalType)))return;
        for(String chat:destinations.chatIds){String hash=destinations.destinationHash(chat);for(var p:mapper.holders(hash,s.symbol)){
            String key;try{key="position:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id+"\n"+hash+"\n"+p.userId+"\n"+p.revision).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Position key unavailable");}
            var stock=stocks.stock(s.symbol);String owner=p.userId.replaceAll("[\\p{Cntrl}<>`*]"," ");
            String label=switch(s.signalType){case "BUY_WATCH"->"买入观察";case "BREAKOUT_WATCH"->"突破观察";case "PULLBACK_STRENGTH"->"缩量回踩转强";case "SELL_WATCH"->"卖出观察";case "RISK_ALERT"->"风险预警";case "STOP_LOSS"->"止损价位";case "TAKE_PROFIT"->"止盈价位";case "RAPID_DROP"->"快速跳水";default->s.signalType;};
            String payload=com.stocksentinel.notification.MessageIdentity.header(com.stocksentinel.notification.MessageIdentity.Kind.RISK,"个人仓位提示",com.stocksentinel.notification.MessageIdentity.strategy(s),"个人持仓",com.stocksentinel.notification.MessageIdentity.phase(s))+"所属用户："+owner+"\n股票："+(stock==null?s.symbol:stock.name+" "+s.symbol)+"\n💰 参考价："+s.referencePrice.stripTrailingZeros().toPlainString()+"元｜"+label+"\n信号依据："+(s.signalAt==null?s.tradeDate+" 收盘":com.stocksentinel.notification.MessageDisplay.time(s.signalAt))+"（北京时间）\n\n📍 仓位约束\n"+note(p,s,dates.today())+"\n"+costComparison(p,s)+"\n\n申报日："+p.reportedOn+"；仓位快照："+com.stocksentinel.notification.MessageDisplay.time(p.updatedAt)+"（北京时间）\n⚠️ 仅为用户申报，可能已变化；成本不参与公共规则触发。本地仓位规则生成，不额外调用模型；不自动下单、不自动修改仓位。";
            mapper.enqueue(id,key,chat,payload);
        }}
    }
}
