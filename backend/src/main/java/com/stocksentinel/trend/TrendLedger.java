package com.stocksentinel.trend;
import com.fasterxml.jackson.databind.*;import com.stocksentinel.jobs.TradingDatePolicy;import com.stocksentinel.report.ReportMapper;
import com.stocksentinel.personal.*;import com.stocksentinel.stock.WatchlistMapper;import com.stocksentinel.notification.*;
import java.time.*;import java.math.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.transaction.annotation.Transactional;
public class TrendLedger {
    private final TrendMapper mapper;private final PositionMapper positions;private final PositionService positionService;private final WatchlistMapper stocks;
    private final TradingDatePolicy dates;private final ReportMapper calendar;private final Clock clock;private final TrendSettings settings;private final NotificationSettings bot;private final ObjectMapper json;
    public TrendLedger(TrendMapper mapper,PositionMapper positions,PositionService positionService,WatchlistMapper stocks,TradingDatePolicy dates,ReportMapper calendar,Clock clock,TrendSettings settings,NotificationSettings bot,ObjectMapper json){this.mapper=mapper;this.positions=positions;this.positionService=positionService;this.stocks=stocks;this.dates=dates;this.calendar=calendar;this.clock=clock;this.settings=settings;this.bot=bot;this.json=json;}
    private void identity(String group,String user){if(!bot.destinationHashes().contains(group)||user==null||user.isBlank()||user.length()>256||user.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("趋势账户身份无效");}
    private static void price(BigDecimal p){if(p==null||p.signum()<=0||p.scale()>4||p.precision()-p.scale()>14)throw new IllegalArgumentException("价格无效，单位人民币元");}
    private String hash(String group,String user,String message){if(message==null||message.isBlank()||message.length()>512)throw new IllegalArgumentException("缺少消息标识");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((group+"\n"+user+"\n"+message).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
    public Object mine(String group,String user){identity(group,user);return Map.of("lots",mapper.mine(group,user),"note","本人本群趋势批次，实际成交手工申报；可卖仍以当日仓位申报为准，不自动推算T+1");}
    @Transactional public Object account(String group,String user,BigDecimal equity,BigDecimal value){identity(group,user);price(equity);if(value==null||value.signum()<0||value.scale()>4||value.compareTo(equity)>0)throw new IllegalArgumentException("股票市值应在0与账户权益之间");mapper.lock(group+":"+user);mapper.saveAccount(group,user,equity,value,dates.today());return Map.of("status","RECORDED","reportedOn",dates.today(),"note","仅手工风险预算快照，成交后市值不自动重估，使用前请更新");}
    @Transactional public Object buy(String group,String user,String symbol,long qty,BigDecimal price,String message){
        identity(group,user);price(price);if(qty<=0||qty>1000000000L||!"OPEN".equals(dates.calendarStatus(dates.today())))throw new IllegalArgumentException("数量或交易日无效");
        mapper.lock(group+":"+user);String hash=hash(group,user,message);if(mapper.message(hash)>0)return Map.of("status","DUPLICATE");
        var stock=stocks.stock(symbol);if(stock==null||!Set.of("SH","SZ").contains(stock.exchange))throw new IllegalArgumentException("股票不存在或不是沪深股票");
        var p=positions.get(group,user,symbol);long before=p==null?0:p.quantity;long total=Math.addExact(before,qty);if(total>1000000000L)throw new IllegalArgumentException("总持仓超过上限");
        BigDecimal cost=before==0?price:p.costPrice.multiply(BigDecimal.valueOf(before)).add(price.multiply(BigDecimal.valueOf(qty))).divide(BigDecimal.valueOf(total),4,RoundingMode.HALF_UP);
        Long sellable=p==null||before==0?0L:p.reportedOn.equals(dates.today())?p.sellableQuantity:null;
        positionService.set(group,user,symbol,total,cost,sellable);
        var lot=new TrendMapper.Lot();lot.groupHash=group;lot.userId=user;lot.symbol=symbol;lot.entryDate=dates.today();lot.entryAt=OffsetDateTime.now(clock);lot.entryPrice=price;lot.quantity=qty;lot.remaining=qty;lot.status="PENDING_DATA";mapper.newLot(lot);
        initialize(lot);mapper.saveLot(lot);mapper.trade(lot.id,hash,"BUY",qty,price);
        var result=new LinkedHashMap<String,Object>();result.put("status","RECORDED");result.put("lot",lot);result.put("note","实际买入已计入个人总持仓；新买不增加当日可卖。止损是风险参考，不保证成交；若ATR未就绪，不虚构止损。");return result;
    }
    private void initialize(TrendMapper.Lot lot){
        var asOf=calendar.previousOpen(lot.entryDate);if(asOf==null)return;String text=mapper.metrics(lot.symbol,asOf);if(text==null)return;
        try{var m=json.readTree(text);var atr=TrendRules.n(m,"raw_atr14");var stop=TrendRules.initial(lot.entryPrice,atr,settings);if(stop.signum()<=0)return;lot.initialAtr=atr.setScale(8,RoundingMode.HALF_UP);lot.initialStop=stop;lot.stopPrice=stop;lot.stopAsOf=asOf;lot.atrSnapshot=text;lot.status="ACTIVE";}catch(Exception invalid){/* Remain pending; never use a future ATR. */}
    }
    @Transactional public void bindPending(String symbol){for(var lot:mapper.stockLots(symbol))if("PENDING_DATA".equals(lot.status)){initialize(lot);mapper.saveLot(lot);}}
    @Transactional public Object sell(String group,String user,long id,long qty,BigDecimal price,String message){
        identity(group,user);price(price);if(qty<=0||qty>1000000000L||!"OPEN".equals(dates.calendarStatus(dates.today())))throw new IllegalArgumentException("数量或交易日无效");mapper.lock(group+":"+user);String hash=hash(group,user,message);if(mapper.message(hash)>0)return Map.of("status","DUPLICATE");
        var lot=mapper.lot(id,group,user);if(lot==null||qty>lot.remaining)throw new IllegalArgumentException("批次不存在、非本人本群或卖出超过批次剩余");
        var p=positions.get(group,user,lot.symbol);if(!dates.today().isAfter(lot.entryDate)||p==null||!p.reportedOn.equals(dates.today())||p.sellableQuantity==null||p.sellableQuantity<qty||p.quantity<qty)throw new IllegalArgumentException("T+1或当日申报可卖不足，请先核对真实持仓");
        long remaining=p.quantity-qty;positionService.set(group,user,lot.symbol,remaining,remaining==0?null:p.costPrice,p.sellableQuantity-qty);
        lot.remaining-=qty;if(lot.remaining==0)lot.status="CLOSED";mapper.saveLot(lot);mapper.trade(id,hash,"SELL",qty,price);return Map.of("status","RECORDED","lotId",id,"remaining",lot.remaining,"note","仅记录实际卖出并更新个人持仓，不发送订单");
    }
    public Object plan(String group,String user,String symbol,BigDecimal entry,BigDecimal stockValue){
        identity(group,user);price(entry);var date=calendar.previousOpen(dates.today());String cached=date==null?null:mapper.metrics(symbol,date);if(cached==null)return Map.of("status","ATR_NOT_READY","note","尚无入场前完整日线ATR，不计算止损或股数");
        try{var m=json.readTree(cached);var atr=TrendRules.n(m,"raw_atr14");var stop=TrendRules.initial(entry,atr,settings);var out=new LinkedHashMap<String,Object>();out.put("symbol",symbol);out.put("entryPrice",entry);out.put("atrBasis",date);out.put("initialStop",stop);out.put("initialAtr",atr);
            var a=mapper.account(group,user);if(a==null||!dates.today().equals(a.reportedOn)||stockValue==null){out.put("status","ACCOUNT_DATA_REQUIRED");out.put("note","缺少当日账户权益/组合市值或明确该股市值，只给止损参考、不猜数量");return out;}
            out.put("plannedShares",TrendRules.shares(a.equity,a.marketValue,stockValue,entry,stop,symbol,settings));out.put("status","PLAN_ONLY");out.put("note","按手工账户快照计算，不保证成交、亏损或费用；不是买入指令，不会执行交易");return out;
        }catch(IllegalArgumentException invalid){throw invalid;}catch(Exception invalid){throw new IllegalArgumentException("趋势指标无效");}
    }
    @Transactional public void observe(String symbol,JsonNode quote,JsonNode previous,JsonNode closed){
        if(previous==null)return;var today=dates.today();BigDecimal price=TrendRules.n(quote,"price");
        boolean basis=TrendRules.n(quote,"previous_close").setScale(2,RoundingMode.HALF_UP).compareTo(TrendRules.n(previous,"raw_close").setScale(2,RoundingMode.HALF_UP))==0;
        var lots=mapper.stockLots(symbol);
        for(var lot:lots){if(!"ACTIVE".equals(lot.status))continue;
            if(!basis){lot.status="BASIS_REVIEW";mapper.saveLot(lot);notice(lot,"basis:"+today,"🟠 趋势持仓·基准待核对","数据待核对","报价前收与原收盘不一致，可能除权或数据口径变化；暂停该批次自动止损判断，不擅自下调止损。原止损参考 "+lot.stopPrice+" 元。");continue;}
            var p=positions.get(lot.groupHash,lot.userId,symbol);long tracked=lots.stream().filter(l->l.groupHash.equals(lot.groupHash)&&l.userId.equals(lot.userId)).mapToLong(l->l.remaining).sum();
            if(p==null||p.quantity<tracked){notice(lot,"position:"+today,"🟠 趋势持仓·数量待核对","仓位待核对","个人总持仓小于趋势批次合计，暂停该批次判断；请核对并逐批记录实际卖出。");continue;}
            var quoteAt=OffsetDateTime.parse(quote.path("quoted_at").asText());
            if(lot.stopAsOf.isBefore(today)&&!quoteAt.isBefore(lot.entryAt)&&price.compareTo(lot.stopPrice)<=0){String available=p.reportedOn.equals(today)&&p.sellableQuantity!=null?p.sellableQuantity+"股（该股票合计，多个批次不可重复使用）":"未知/已跨日，须重新申报";
                notice(lot,"stop:"+today,"🚨 趋势止损触及·请优先核对","盘中触及","建议操作：核对可卖后评估减仓，不能保证止损价成交。\n参考价 "+price+" 元 ≤ 已生效止损 "+lot.stopPrice+" 元\n批次剩余 "+lot.remaining+" 股；当日申报可卖："+available+"\n止损依据日 "+lot.stopAsOf+"；新买股份受T+1限制；跳空、跌停可能无法卖出。不自动下单。");}
            if(closed!=null&&!today.isBefore(lot.entryDate)&&(lot.lastCloseDate==null||today.isAfter(lot.lastCloseDate))){var close=TrendRules.n(closed,"raw_close");lot.highestClose=lot.highestClose==null?close:lot.highestClose.max(close);var prior=lot.stopPrice;lot.stopPrice=TrendRules.trail(prior,lot.highestClose,TrendRules.n(closed,"raw_atr14"),settings);lot.stopAsOf=today;lot.lastCloseDate=today;mapper.saveLot(lot);
                if(lot.stopPrice.compareTo(prior)>0)notice(lot,"trail:"+today,"趋势跟踪止损上移","次日生效","建议操作：保留趋势观察，留意新的风险参考。\n原止损 "+prior+" → 新止损 "+lot.stopPrice+" 元\n最高完整收盘 "+lot.highestClose+" 元；新止损从下一开市日生效。仅参考，不保证成交，不自动下单。");}
        }
    }
    private void notice(TrendMapper.Lot lot,String key,String action,String phase,String text){if(!bot.enabled)return;String chat=bot.chatIds.stream().filter(c->bot.destinationHash(c).equals(lot.groupHash)).findFirst().orElse(null);if(chat==null)return;var s=stocks.stock(lot.symbol);String name=s==null?lot.symbol:s.name.replaceAll("[\\p{Cntrl}<>`*_]"," ")+" "+lot.symbol;String owner=lot.userId.replaceAll("[\\p{Cntrl}<>`*]"," ");mapper.notice("trend-lot:"+lot.id+":"+key,chat,MessageIdentity.header(MessageIdentity.Kind.RISK,action,"趋势跟踪·ATR止损","个人持仓",phase)+"所属用户："+owner+"\n股票："+name+"｜批次#"+lot.id+"\n"+text+"\n北京时间 "+MessageDisplay.time(OffsetDateTime.now(clock)));}
}
