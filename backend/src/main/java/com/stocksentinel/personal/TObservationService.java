package com.stocksentinel.personal;

import com.stocksentinel.intraday.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.notification.NotificationSettings;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.commands.GroupAssistantMapper;
import java.time.*;
import java.math.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.stocksentinel.personal.TObservationRules.*;

@Service
public class TObservationService {
    @org.springframework.beans.factory.annotation.Autowired private TTradeLedgerMapper ledger;
    private final TObservationMapper mapper;private final NotificationSettings settings;private final TradingDatePolicy dates;
    private final WatchlistMapper stocks;private final GroupAssistantMapper people;private final Clock clock;private final boolean enabled;
    public TObservationService(TObservationMapper mapper,NotificationSettings settings,TradingDatePolicy dates,WatchlistMapper stocks,GroupAssistantMapper people,Clock clock,@Value("${t-observation.enabled:false}") boolean enabled){this.mapper=mapper;this.settings=settings;this.dates=dates;this.stocks=stocks;this.people=people;this.clock=clock;this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    private OffsetDateTime now(){return OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));}
    public List<String> symbols(){return enabled?settings.destinationHashes().stream().flatMap(g->mapper.symbols(g,dates.today()).stream()).distinct().sorted().toList():List.of();}
    private void identity(String group,String user){if(!enabled)throw new IllegalArgumentException("做T观察未启用");if(!settings.destinationHashes().contains(group)||user==null||user.isBlank()||user.length()>256||user.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("做T身份无效");}
    private TObservationMapper.Day state(PositionMapper.Position p,LocalDate day){
        mapper.lock(p.groupHash+"\n"+p.userId+"\n"+p.symbol+"\n"+day);
        var seed=new TObservationMapper.Day();seed.groupHash=p.groupHash;seed.userId=p.userId;seed.symbol=p.symbol;seed.tradeDate=day;
        seed.positionRevision=p.revision;seed.baselineQuantity=p.quantity;seed.baselineSellable=p.sellableQuantity;mapper.seed(seed);
        return mapper.get(p.groupHash,p.userId,p.symbol,day);
    }
    @Transactional public int evaluate(String symbol,IntradayContracts.Snapshot minutes,Rsi rsi){
        return evaluate(symbol,minutes,rsi,null);
    }
    @Transactional public int evaluate(String symbol,IntradayContracts.Snapshot minutes,Rsi rsi,Long scanJobId){
        var now=now();if(!enabled||!entryTime(now.toLocalTime())||!"OPEN".equals(dates.calendarStatus(now.toLocalDate())))return 0;
        IntradayValidation.snapshot(symbol,minutes,now);
        if(!fresh(symbol,rsi,now))throw new IntradayValidation.Skip("T_RSI_STALE_OR_INVALID");
        // Never combine an indicator ending after the price snapshot, even if both are individually recent.
        var bar=minutes.bars().getLast();if(rsi.fiveAt().isAfter(bar.barAt()))throw new IntradayValidation.Skip("T_RSI_PRICE_NOT_ALIGNED");
        int count=0;
        for(String group:settings.destinationHashes())for(var p:mapper.positions(group,symbol,now.toLocalDate())){
            var d=state(p,now.toLocalDate());
            if(!Objects.equals(d.positionRevision,p.revision)){d.lastCode="POSITION_CHANGED_PAUSED";mapper.save(d);continue;}
            if(d.lastFiveAt!=null&&!rsi.fiveAt().isAfter(d.lastFiveAt))continue;
            d.lastFiveAt=rsi.fiveAt();d.fifteenAt=rsi.fifteenAt();d.rsi5=rsi.rsi5();d.rsi15=rsi.rsi15();d.lastCode="NO_RESONANCE";
            d.scanJobId=scanJobId;d.indicatorObservedAt=rsi.observedAt();d.evaluatedAt=now;d.rsiSource=rsi.source();
            if(d.buyFilled>0&&d.sellFilled>0){d.lastCode=d.buyFilled.equals(d.sellFilled)?"CYCLE_COMPLETED":"AWAIT_MANUAL_RESTORE";mapper.save(d);continue;}
            String action=action(rsi);long net=d.buyFilled-d.sellFilled;
            if("SELL".equals(action)&&!d.sellObserved&&net>=0){
                d.sellObserved=true;d.lastCode="SELL_OBSERVATION";count+=event(d,"SELL",bar.closePrice());
            }else if("BUY".equals(action)&&!d.buyObserved&&net<=0){
                d.buyObserved=true;d.lastCode=d.sellFilled>0?"BUYBACK_OBSERVATION":"BUY_OBSERVATION";count+=event(d,"BUY",bar.closePrice());
            }else if("SELL".equals(action)&&d.sellObserved)d.lastCode="SELL_ALREADY_OBSERVED";
            else if("BUY".equals(action)&&d.buyObserved)d.lastCode="BUY_ALREADY_OBSERVED";
            mapper.save(d);
        }
        return count;
    }
    @Transactional public Object status(String group,String user,String symbol){
        identity(group,user);var p=mapper.position(group,user,symbol);var d=mapper.get(group,user,symbol,dates.today());
        var out=new LinkedHashMap<String,Object>();out.put("enabled",enabled);out.put("symbol",symbol);var stock=stocks.stock(symbol);out.put("name",stock==null?"名称未记录":stock.name);
        out.put("tradeDate",dates.today());out.put("entryTime",entryTime(now().toLocalTime()));out.put("calendar",dates.calendarStatus(dates.today()));
        out.put("positionReady",ready(p,dates.today()));out.put("state",d);
        out.put("eligibilityCode",eligibility(p,dates.today()));
        out.put("ledger",ledger.audit(group,user,symbol,dates.today(),0).stream().map(this::eventView).toList());
        out.put("previousUnbalancedDays",mapper.previousOutstanding(group,user,symbol,dates.today()).stream().map(old->Map.of("tradeDate",old.tradeDate,"netAddedShares",old.buyFilled-old.sellFilled)).toList());
        if(d!=null)out.put("reconciliation",Map.of("baselineQuantity",d.baselineQuantity,"expectedQuantity",d.baselineQuantity+d.buyFilled-d.sellFilled,"reportedQuantity",p==null?0:p.quantity,"positionRevisionChanged",p==null||!Objects.equals(p.revision,d.positionRevision),"note","成交台账不自动覆盖手工仓位；变更后需核对确认，昨日可卖不沿用"));
        out.put("latestIntradayScan",mapper.latestScan(symbol,dates.today()));
        out.put("rules","Wilder RSI(14)：15分钟>50且5分钟>80减仓观察；15分钟<50且5分钟<20买入/回补观察；完整K线，10:00起，14:50停止新观察；每阶段每天最多一次");
        out.put("indicatorFresh",d!=null&&d.lastFiveAt!=null&&now().minusMinutes(6).isBefore(d.lastFiveAt)&&d.lastFiveAt.toLocalDate().equals(dates.today()));
        out.put("note","这是最近已落库扫描结果，不会立即拉取行情。可卖仅申报当日有效；仓位更新后暂停，核对差异并明确恢复后才继续，台账保留。回补并非保证盈利，模型不得臆造实时行情或成交。");return out;
    }
    public static boolean ready(PositionMapper.Position p,LocalDate day){return p!=null&&p.quantity!=null&&p.quantity>0&&day.equals(p.reportedOn)&&p.sellableQuantity!=null&&p.sellableQuantity>0;}
    public static String eligibility(PositionMapper.Position p,LocalDate day){
        if(p==null)return "POSITION_NOT_REPORTED";if(p.quantity==null||p.quantity<=0)return "POSITION_EMPTY";
        if(!day.equals(p.reportedOn))return "SELLABLE_STALE";if(p.sellableQuantity==null)return "SELLABLE_UNKNOWN";
        return p.sellableQuantity<=0?"SELLABLE_ZERO":"READY";
    }
    @Transactional public Object fill(String group,String user,String symbol,String side,long quantity,BigDecimal price,String message){
        identity(group,user);var now=now();if(!"OPEN".equals(dates.calendarStatus(now.toLocalDate()))||now.toLocalTime().isBefore(LocalTime.of(10,0))||!now.toLocalTime().isBefore(LocalTime.of(15,0)))throw new IllegalArgumentException("仅接受当日10:00至15:00的明确成交申报，不补造历史成交");
        if(!Set.of("BUY","SELL").contains(side)||quantity<=0||quantity>1000000000L||!IntradayValidation.price(price)||message==null||message.isBlank())throw new IllegalArgumentException("成交方向、股数或人民币成交价无效");
        var p=mapper.position(group,user,symbol);mapper.lock(group+"\n"+user+"\n"+symbol+"\n"+dates.today());
        var d=mapper.get(group,user,symbol,dates.today());if(d==null)throw new IllegalArgumentException("本群本人该股尚未建立今日做T观察，请先申报今日持仓及可卖股数并等待扫描");
        String hash=digest(group+"\n"+user+"\n"+message);var duplicate=ledger.byMessage(hash);if(duplicate!=null)return Map.of("status","ALREADY_RECORDED","eventId",duplicate.id,"note","同一消息不会重复累计成交");
        if(p==null)throw new IllegalArgumentException("持仓记录不存在，无法核验");
        long other="BUY".equals(side)?d.sellFilled:d.buyFilled;
        if(("BUY".equals(side)&&!d.buyObserved&&other==0)||("SELL".equals(side)&&!d.sellObserved&&other==0))throw new IllegalArgumentException("尚无对应方向做T观察；只记录本策略观察后的明确实际成交");
        var event=trade(d,"FILL",side,quantity,price,hash,null);ledger.insert(event);rollup(d);
        return Map.of("status","RECORDED","eventId",event.id,"netAddedShares",d.buyFilled-d.sellFilled,"note","支持分笔申报，仅保存本人手工成交台账，不执行订单，不自动改写仓位；更正或撤销需明确成交编号");
    }
    @Transactional public Object amend(String group,String user,long rootId,boolean cancel,Long quantity,BigDecimal price,String message){
        identity(group,user);fillTime();if(message==null||message.isBlank())throw new IllegalArgumentException("缺少有效消息编号");var root=ledger.root(rootId,group,user);
        if(root==null||!dates.today().equals(root.tradeDate))throw new IllegalArgumentException("仅能更正或撤销本人本群当日原始成交编号");
        mapper.position(group,user,root.symbol);mapper.lock(group+"\n"+user+"\n"+root.symbol+"\n"+dates.today());
        var d=mapper.get(group,user,root.symbol,dates.today());String hash=digest(group+"\n"+user+"\n"+message);
        var duplicate=ledger.byMessage(hash);if(duplicate!=null)return Map.of("status","ALREADY_RECORDED","eventId",duplicate.id);
        if("VOID".equals(ledger.latest(rootId,group,user).kind))throw new IllegalArgumentException("该成交已撤销，不再次修改；请查询台账后明确重新申报");
        if(!cancel&&(quantity==null||quantity<=0||quantity>1000000000L||!IntradayValidation.price(price)))throw new IllegalArgumentException("更正需要明确有效股数和人民币成交价");
        var event=trade(d,cancel?"VOID":"CORRECT",root.side,cancel?null:quantity,cancel?null:price,hash,rootId);ledger.insert(event);rollup(d);
        return Map.of("status",cancel?"VOIDED":"CORRECTED","eventId",event.id,"rootEventId",rootId,"netAddedShares",d.buyFilled-d.sellFilled,"note","保留原始事件和更正审计，不代表券商成交撤单，不自动改仓");
    }
    public Object trades(String group,String user,String symbol,LocalDate day,int offset){
        identity(group,user);if(day==null||day.isAfter(dates.today())||day.isBefore(LocalDate.of(2000,1,1))||offset<0||offset>10000)throw new IllegalArgumentException("无效成交查询日期或分页");
        var rows=ledger.audit(group,user,symbol,day,offset);var totals=ledger.totals(group,user,symbol,day);
        return Map.of("tradeDate",day,"symbol",symbol,"events",rows.stream().map(this::eventView).toList(),"totals",totals,"offset",offset,"nextOffset",offset+rows.size(),"mayHaveMore",rows.size()==20,"note","仅本人本群手工成交台账及审计，净额按每笔最新更正或撤销重算，不是券商同步；最多20条一页");
    }
    @Transactional public Object reconcile(String group,String user,String symbol){
        identity(group,user);fillTime();var p=mapper.position(group,user,symbol);
        if(!ready(p,dates.today()))throw new IllegalArgumentException("请先明确申报今日正持仓及有效可卖股数");
        mapper.lock(group+"\n"+user+"\n"+symbol+"\n"+dates.today());var d=mapper.get(group,user,symbol,dates.today());
        if(d==null)throw new IllegalArgumentException("今日尚未建立观察，无需恢复");
        if(p.quantity!=d.baselineQuantity+d.buyFilled-d.sellFilled||p.sellableQuantity>d.baselineSellable-d.sellFilled)throw new IllegalArgumentException("申报仓位或可卖与原底仓及成交台账不一致，请先核对分笔成交；不能把今日买入计为可卖");
        ledger.reconcile(group,user,symbol,dates.today(),p.revision);
        return Map.of("status","BASELINE_RECONCILED","note","已核对并恢复原周期观察；保留原底仓、成交、当日提醒去重与14:50差额检查，不自动推算T+1");
    }
    private void fillTime(){var t=now();if(!"OPEN".equals(dates.calendarStatus(t.toLocalDate()))||t.toLocalTime().isBefore(LocalTime.of(10,0))||!t.toLocalTime().isBefore(LocalTime.of(15,0)))throw new IllegalArgumentException("仅接受当日10:00至15:00明确申报，不补造历史成交");}
    private TTradeLedgerMapper.Event trade(TObservationMapper.Day d,String kind,String side,Long quantity,BigDecimal price,String hash,Long root){
        var e=new TTradeLedgerMapper.Event();e.groupHash=d.groupHash;e.userId=d.userId;e.symbol=d.symbol;e.tradeDate=d.tradeDate;e.kind=kind;e.side=side;e.quantity=quantity;e.price=price;e.messageHash=hash;e.rootId=root;return e;
    }
    private void rollup(TObservationMapper.Day d){
        var t=ledger.totals(d.groupHash,d.userId,d.symbol,d.tradeDate);
        if(t.buyQuantity>d.baselineSellable||t.sellQuantity>d.baselineSellable||("SELL".equals(t.firstSide)&&t.buyQuantity>t.sellQuantity)||("BUY".equals(t.firstSide)&&t.sellQuantity>t.buyQuantity))throw new IllegalArgumentException("累计成交超过底仓可卖上限或反向恢复数量；请核对台账，不据此扩大做T敞口");
        d.buyFilled=t.buyQuantity;d.sellFilled=t.sellQuantity;
        d.buyPrice=t.buyQuantity==0?null:t.buyAmount.divide(BigDecimal.valueOf(t.buyQuantity),4,RoundingMode.HALF_UP);
        d.sellPrice=t.sellQuantity==0?null:t.sellAmount.divide(BigDecimal.valueOf(t.sellQuantity),4,RoundingMode.HALF_UP);
        d.lastCode="LEDGER_UPDATED";mapper.save(d);
    }
    private Map<String,Object> eventView(TTradeLedgerMapper.Event e){var out=new LinkedHashMap<String,Object>();out.put("eventId",e.id);out.put("rootEventId",e.rootId);out.put("kind",e.kind);out.put("side",e.side);out.put("quantity",e.quantity);out.put("price",e.price);out.put("recordedAt",e.createdAt);out.put("origin",e.origin);return out;}
    @Transactional public int restore(){
        var now=now();if(!enabled||now.toLocalTime().isBefore(LocalTime.of(14,50))||!now.toLocalTime().isBefore(LocalTime.of(15,0))||!"OPEN".equals(dates.calendarStatus(now.toLocalDate())))return 0;
        int count=0;for(String group:settings.destinationHashes())for(var candidate:mapper.outstanding(group,now.toLocalDate())){
            mapper.lock(group+"\n"+candidate.userId+"\n"+candidate.symbol+"\n"+now.toLocalDate());
            var d=mapper.get(group,candidate.userId,candidate.symbol,now.toLocalDate());
            if(!d.restoreObserved&&!d.buyFilled.equals(d.sellFilled)){d.restoreObserved=true;d.lastCode="RESTORE_OBSERVATION";count+=event(d,"RESTORE",null);mapper.save(d);}
        }return count;
    }
    private int event(TObservationMapper.Day d,String phase,BigDecimal price){
        String chat=settings.chatIds.stream().filter(c->settings.destinationHash(c).equals(d.groupHash)).findFirst().orElseThrow();
        var stock=stocks.stock(d.symbol);String alias=people.displayName(d.groupHash,d.userId),owner=alias==null?d.userId:alias+"（"+d.userId+"）";
        long net=d.buyFilled-d.sellFilled;
        String detail;
        if("RESTORE".equals(phase))detail="临近收盘，请核验实际成交与底仓。按手工台账，"+(net>0?"较底仓多 "+net+" 股，观察恢复卖出；新买股份受T+1限制，只能使用实际可卖底仓":"较底仓少 "+(-net)+" 股，观察恢复回补；需要自行确认资金与成交条件")+"。行情或流动性可能导致无法恢复，请及时人工处理。";
        else if("SELL".equals(phase))detail="高位减仓观察：15分钟RSI>50，5分钟RSI>80。"+(net>0?"已申报额外买入 "+net+" 股，减仓观察不超过该数量。":"当日申报可卖上限 "+d.baselineSellable+" 股；这是上限，不建议全部卖出。")+"存在继续上涨、卖飞风险。";
        else detail="低位"+(d.sellFilled>0?"回补":"买入")+"观察：15分钟RSI<50，5分钟RSI<20。"+(net<0?"已申报减仓差额 "+(-net)+" 股，回补观察不超过该数量。":"尚未申报减仓；若先买后卖，需要额外资金及实际可卖底仓；缺少资金信息，不给出买入股数。")+"存在继续下跌风险，不保证盈利。";
        String payload=com.stocksentinel.notification.MessageIdentity.header(com.stocksentinel.notification.MessageIdentity.Kind.T_OBSERVATION,"RESTORE".equals(phase)?"收盘恢复底仓":"SELL".equals(phase)?"减仓观察":"买入/回补观察","RESTORE".equals(phase)?"做T成交账本":"双周期RSI","个人持仓","RESTORE".equals(phase)?"收盘前检查":"盘中完整K线")+"所属用户："+safe(owner)+"\n股票："+safe(stock==null?"名称未记录":stock.name)+" "+d.symbol+"\n\n📍 观察说明\n"+detail
            +(price==null?"":"\n\n💰 完整分钟参考价："+price.stripTrailingZeros().toPlainString()+" 元\n📊 RSI：5分钟="+d.rsi5.setScale(2,RoundingMode.HALF_UP)+"，15分钟="+d.rsi15.setScale(2,RoundingMode.HALF_UP)+"\n指标依据：5分钟 "+com.stocksentinel.notification.MessageDisplay.time(d.lastFiveAt)+"；15分钟 "+com.stocksentinel.notification.MessageDisplay.time(d.fifteenAt)+"（北京时间）")
            +"\n🕒 提醒时间："+com.stocksentinel.notification.MessageDisplay.time(now())+"（北京时间）\n\n⚠️ RSI为前复权指标，参考价不复权。规则未经回测；注意费用、滑点及T+1。成交需本人明确申报，不自动下单。";
        String key="t-observe:"+digest(d.groupHash+"\n"+d.userId+"\n"+d.symbol+"\n"+d.tradeDate)+":"+phase;
        mapper.notice(key,d,phase);return mapper.enqueue(key,chat,payload);
    }
    private static String safe(String s){return s.replaceAll("[\\p{Cntrl}<>`*]"," ");}
    private static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Identity hash unavailable");}}
}
