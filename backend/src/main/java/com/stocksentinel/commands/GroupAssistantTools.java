package com.stocksentinel.commands;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.strategy.StrategyMapper;
import com.stocksentinel.signal.SignalMapper;
import com.stocksentinel.pullback.*;
import com.stocksentinel.report.ReportMapper;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.notification.NotificationSettings;
import java.util.*;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

@Service
public class GroupAssistantTools {
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.personal.PersonalPriceService personal;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.report.SelectionExplanationService selections;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.personal.PositionService positions;
    @org.springframework.beans.factory.annotation.Autowired private com.stocksentinel.personal.TObservationService tObservation;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<com.stocksentinel.trend.TrendLedger> trendLedgers;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<com.stocksentinel.trend.TrendService> trendServices;
    private final GroupAssistantMapper mapper;private final WatchlistMapper stocks;private final MonitorSubscriptionService subscriptions;
    private final ObjectProvider<MonitorIntentClient> intent;private final StrategyMapper strategies;private final SignalMapper signals;
    private final PullbackService pullback;private final ReportMapper reports;private final TradingDatePolicy dates;
    private final NotificationSettings bot;private final ObjectMapper json;
    private final boolean dailySchedule,digestEnabled,intradayEnabled;
    @Value("${intraday.schedule-enabled:false}") private boolean intradayScheduleEnabled;
    @Value("${intraday.ma60-enabled:false}") private boolean ma60Enabled;
    @Value("${intraday.ma60-cooldown-minutes:15}") private int ma60Cooldown;
    public GroupAssistantTools(GroupAssistantMapper mapper,WatchlistMapper stocks,MonitorSubscriptionService subscriptions,ObjectProvider<MonitorIntentClient> intent,
            StrategyMapper strategies,SignalMapper signals,PullbackService pullback,ReportMapper reports,TradingDatePolicy dates,NotificationSettings bot,ObjectMapper json,
            @Value("${jobs.schedule-enabled:false}") boolean dailySchedule,@Value("${pullback.digest-enabled:false}") boolean digestEnabled,@Value("${intraday.enabled:false}") boolean intradayEnabled) {
        this.mapper=mapper;this.stocks=stocks;this.subscriptions=subscriptions;this.intent=intent;this.strategies=strategies;this.signals=signals;
        this.pullback=pullback;this.reports=reports;this.dates=dates;this.bot=bot;this.json=json;this.dailySchedule=dailySchedule;this.digestEnabled=digestEnabled;this.intradayEnabled=intradayEnabled;
    }
    public String group(GroupMessage m) {
        if(!bot.botId.equals(m.botId)||!bot.accepts(m.chatId)||m.userId==null||m.userId.isBlank()||m.userId.length()>256||m.userId.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid callback context");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((m.botId+"\n"+m.chatId).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Identity scope unavailable");}
    }
    public Object execute(GroupMessage m,String name,JsonNode args,AtomicBoolean writeUsed) {
        try {
            String group=group(m);
            return switch(name) {
                case "get_trend_status" -> {fields(args);var service=trendServices.getIfAvailable();yield service==null?Map.of("enabled",false,"status","DISABLED"):service.status();}
                case "list_trend_positions" -> {fields(args);yield trendLedger().mine(group,m.userId);}
                case "set_trend_account" -> {
                    fields(args,"equity","market_value");if(!args.path("equity").isNumber()||!args.path("market_value").isNumber())throw new IllegalArgumentException("请明确提供账户权益和股票市值");var equity=args.path("equity").decimalValue();var value=args.path("market_value").decimalValue();
                    if(!TrendIntent.account(m.text,equity,value)||!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("请单独说：设置趋势账户资产100000元，股票市值20000元");yield trendLedger().account(group,m.userId,equity,value);
                }
                case "record_trend_buy" -> {
                    fields(args,"stock_query","quantity","price");String query=text(args,"stock_query");var qty=args.path("quantity");var price=args.path("price");if(!qty.isIntegralNumber()||!qty.canConvertToLong()||!price.isNumber()||!TrendIntent.buy(m.text,query,qty.asLong(),price.decimalValue()))throw new IllegalArgumentException("请单独明确说：记录四川美丰趋势买入100股，成交价7.8元；必须是已实际成交");String symbol=resolve(query);if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多一次写操作");yield trendLedger().buy(group,m.userId,symbol,qty.asLong(),price.decimalValue(),m.msgId);
                }
                case "record_trend_sell" -> {
                    fields(args,"lot_id","quantity","price");var id=args.path("lot_id");var qty=args.path("quantity");var price=args.path("price");if(!id.isIntegralNumber()||!id.canConvertToLong()||!qty.isIntegralNumber()||!qty.canConvertToLong()||!price.isNumber()||!TrendIntent.sell(m.text,id.asLong(),qty.asLong(),price.decimalValue())||!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("请单独明确说：记录趋势批次123卖出100股，成交价8元；仅本人批次实际成交");yield trendLedger().sell(group,m.userId,id.asLong(),qty.asLong(),price.decimalValue(),m.msgId);
                }
                case "get_trend_plan" -> {
                    fields(args,"stock_query","entry_price","stock_market_value");String query=text(args,"stock_query");var p=args.path("entry_price");var v=args.path("stock_market_value");if(!p.isNumber()||(!v.isMissingNode()&&!v.isNumber()))throw new IllegalArgumentException("缺少明确计划价和已有该股市值，不猜测");var value=v.isMissingNode()?null:v.decimalValue();if(!TrendIntent.plan(m.text,p.decimalValue(),value))throw new IllegalArgumentException("请明确说：计划四川美丰趋势买入，计划价8元，已有该股市值0元");yield trendLedger().plan(group,m.userId,resolve(query),p.decimalValue(),value);
                }
                case "get_t_observation" -> {fields(args,"stock_query");yield tObservation.status(group,m.userId,resolve(text(args,"stock_query")));}
                case "list_t_trades" -> {
                    fields(args,"stock_query","trade_date","offset");var offset=args.path("offset");
                    if(!offset.isMissingNode()&&(!offset.isIntegralNumber()||!offset.canConvertToInt()))throw new IllegalArgumentException("无效分页");
                    java.time.LocalDate day=dates.today();if(args.has("trade_date")){try{day=java.time.LocalDate.parse(text(args,"trade_date"));}catch(java.time.format.DateTimeParseException invalid){throw new IllegalArgumentException("日期需为yyyy-MM-dd");}}
                    yield tObservation.trades(group,m.userId,resolve(text(args,"stock_query")),day,offset.asInt(0));
                }
                case "correct_t_fill","cancel_t_fill" -> {
                    boolean cancel="cancel_t_fill".equals(name);fields(args,"event_id","quantity","price");var id=args.path("event_id");
                    if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<1)throw new IllegalArgumentException("请提供本人当日原始成交编号");
                    var q=args.path("quantity");var p=args.path("price");
                    if(!cancel&&(!q.isIntegralNumber()||!q.canConvertToLong()||!p.isNumber()))throw new IllegalArgumentException("更正需明确股数与人民币成交价");
                    Long quantity=cancel?null:q.longValue();java.math.BigDecimal price=cancel?null:p.decimalValue();
                    if((cancel&&(args.has("quantity")||args.has("price")))||!TObservationIntent.amendment(m.text,id.longValue(),cancel,quantity,price))throw new IllegalArgumentException("请明确说‘撤销做T成交123’或‘更正做T成交123为100股，成交价8元’，仅修改本人手工记录，不撤销券商订单");
                    if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    yield tObservation.amend(group,m.userId,id.longValue(),cancel,quantity,price,m.msgId);
                }
                case "resume_t_observation" -> {
                    fields(args,"stock_query");String query=text(args,"stock_query");
                    if(!normalize(m.text).matches("确认"+java.util.regex.Pattern.quote(normalize(query))+"仓位基准并恢复做[Tt]观察[。.!！]?"))throw new IllegalArgumentException("请先核对仓位和成交，再明确说‘确认四川美丰仓位基准并恢复做T观察’");
                    if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    yield tObservation.reconcile(group,m.userId,resolve(query));
                }
                case "record_t_fill" -> {
                    fields(args,"stock_query","side","quantity","price");String query=text(args,"stock_query"),side=text(args,"side");
                    var q=args.path("quantity");var price=args.path("price");
                    if(!q.isIntegralNumber()||!q.canConvertToLong()||!price.isNumber()||!TObservationIntent.explicit(m.text,query,side,q.longValue(),price.decimalValue()))throw new IllegalArgumentException("请单独明确说‘记录四川美丰做T卖出成交200股，成交价8元’或‘记录四川美丰做T买入成交200股，成交价7.8元’；仅记录已实际成交，不能推算或代他人申报");
                    String symbol=resolve(query);if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    yield tObservation.fill(group,m.userId,symbol,side,q.longValue(),price.decimalValue(),m.msgId);
                }
                case "list_watchlist" -> {
                    fields(args,"scope","offset");String scope=text(args,"scope");if(!Set.of("mine","group").contains(scope))throw new IllegalArgumentException("scope必须是mine或group");
                    var offsetNode=args.path("offset");if(!offsetNode.isMissingNode() && (!offsetNode.isIntegralNumber()||!offsetNode.canConvertToInt()))throw new IllegalArgumentException("无效分页");
                    int offset=offsetNode.asInt(0);if(offset<0||offset>10000)throw new IllegalArgumentException("无效分页");
                    var rows=mapper.watches(group,"mine".equals(scope)?m.userId:null,offset);var result=new ArrayList<Map<String,Object>>();
                    for(var row:rows){var item=new LinkedHashMap<String,Object>(row);var owners=mapper.owners(group,row.get("symbol").toString());item.put("followers",owners);if(owners.isEmpty())item.put("origin","来源未记录，无法判断历史关注人；本地API添加的公共关注也归此类");result.add(item);}
                    String display=mapper.displayName(group,m.userId);
                    yield Map.of("scope",scope,"offset",offset,"nextOffset",offset+rows.size(),"mayHaveMore",rows.size()==20,"stocks",result,"requester",Map.of("userId",m.userId,"displayName",display==null?"未设置":display,"nameSource","用户自设，非企业通讯录核验"),"note","mine仅包含上线后明确记录的本人关注；group包含本群共用监控和来源未记录的历史股票。列表最多20只，关注人最多20人。");
                }
                case "get_selection_explanation" -> {
                    fields(args,"stock_query");yield selections.explain(resolve(text(args,"stock_query")),dates.today());
                }
                case "get_strategy_info" -> {
                    fields(args);var definition=strategies.find("SWING_TREND_V1");
                    var daily=new LinkedHashMap<String,Object>();daily.put("configured",definition!=null);daily.put("scheduleEnabled",dailySchedule);
                    if(definition!=null){daily.put("enabled",definition.enabled);daily.put("parameters",json.readTree(definition.parameters));}
                    daily.put("buy","MACD金叉且收盘高于MA20且MA20向上，成交量达此前5日均量的配置倍数");daily.put("sell","MACD死叉且收盘低于MA20");daily.put("risk","从MA60上方或相等位置向下穿越才触发");
                    yield Map.of("tObservation",Map.of("enabled",tObservation!=null&&tObservation.enabled(),"rules","完整5/15分钟Wilder RSI14：15分钟>50且5分钟>80减仓；15分钟<50且5分钟<20买入或回补；当日申报正持仓且可卖>0才观察；10:00起、14:50停止新观察，成交净差额提示恢复底仓；每方向每天一次，不自动下单"),"daily",daily,"intraday",Map.of("enabled",intradayEnabled,"rules","五分钟涨跌幅阈值、突破此前20分钟高点且量能达配置倍数、用户配置的止盈止损；逐股实际阈值请查关注列表；每分钟尝试，源延迟可能跳过"),
                        "intradayMa60Risk",Map.of("enabled",ma60Enabled,"cooldownMinutes",ma60Cooldown,"rules","关注股连续完整分钟收盘从上一开市日60日均线换算价上方或相等位置跌至下方；首次建立或变更基准不追发，持续低于不重复，分钟缺口不跨越判断；基准不足或疑似除权则跳过，可查询逐股就绪原因"),
                        "pullback",Map.of("parameters",pullback.settings(),"entry","回踩前20日涨幅达阈值；连续收盘回调2～5天、相对回踩前20日高点回撤达阈值、回调均量/此前5日均量达阈值、MA20向上且收盘在其上；完整参数以parameters为准","confirmation","完整分钟收盘突破前一交易日不复权高点，量能达到此前20分钟均量的配置倍数；同候选一次转强提醒","digestEnabled",digestEnabled,"digest","交易日09:40、15:30各一次，最多5只；早晚仍在有效观察池的同一股票可以重复推荐，同一时间槽不重复发送"),"boundary","只解释规则；初始回踩参数未回测，不自动下单；个人仓位约束请查询本人仓位工具，公共策略不推算账户资金");
                }
                case "get_monitoring_status" -> {
                    fields(args);var run=reports.run(dates.today());var result=new LinkedHashMap<String,Object>();result.put("tradeDate",dates.today());result.put("calendar",dates.calendarStatus(dates.today()));result.put("intradayEnabled",intradayEnabled);result.put("intradayScheduleEnabled",intradayScheduleEnabled);result.put("personalPriceEnabled",personal.enabled());result.put("screening",run==null?Map.of("status","NOT_STARTED"):Map.of("status",run.status,"asOf",run.asOf,"total",run.total,"coverage",reports.coverage(dates.today())));
                    result.put("tObservationEnabled",tObservation!=null&&tObservation.enabled());
                    result.put("pullbackPool",pullback.pool().stream().limit(5).map(c->Map.of("symbol",c.symbol,"name",c.name,"dataDate",c.dataDate,"referenceHigh",c.referenceHigh)).toList());result.put("note","以上是数据库与配置状态，不是实时行情");yield result;
                }
                case "search_stocks" -> {fields(args,"query");yield stocks.search(text(args,"query"),10,0);}
                case "set_position" -> {
                    fields(args,"stock_query","quantity","cost_price","sellable_quantity");String query=text(args,"stock_query");
                    String source=normalize(m.text);
                    if(readOnly(m.text)||!source.contains(normalize(query))||!source.matches(".*(设置|记录|更新).*"))throw new IllegalArgumentException("请本条明确设置股票、持仓股数、成本价及可卖股数");
                    for(String denied:List.of("代他人","替","别人","其他人","他的","她的","假设","如果","举例","例如","不要","不用","别","不想","不需要","不能","取消","停止"))if(source.contains(denied))throw new IllegalArgumentException("只接受当前用户明确申报，不接受代他人或假设记录");
                    long quantity=positionQuantity(m.text,args,"quantity","(?:持仓|持股|仓位)");
                    Long sellable=args.has("sellable_quantity")?positionQuantity(m.text,args,"sellable_quantity","可卖"):null;
                    java.math.BigDecimal cost=null;
                    if(args.has("cost_price")){if(!args.path("cost_price").isNumber())throw new IllegalArgumentException("成本价必须为数值");cost=args.path("cost_price").decimalValue();if(!literalNumber(m.text,"成本(?:价)?",cost,"元"))throw new IllegalArgumentException("成本价必须对应本条明确给出的人民币金额");}
                    String symbol=resolve(query);if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    yield positions.set(group,m.userId,symbol,quantity,cost,sellable);
                }
                case "list_my_positions" -> {
                    fields(args,"stock_query","offset");var offset=args.path("offset");if(!offset.isMissingNode()&&(!offset.isIntegralNumber()||!offset.canConvertToInt()))throw new IllegalArgumentException("分页无效");
                    yield Map.of("positions",positions.mine(group,m.userId,args.has("stock_query")?resolve(text(args,"stock_query")):null,offset.asInt(0)),"scope","仅本人在本群手工申报的仓位，20只一页，不是券商实时持仓");
                }
                case "get_position_advice" -> {fields(args,"stock_query");yield positions.advice(group,m.userId,resolve(text(args,"stock_query")));}
                case "set_personal_price_alert" -> {
                    fields(args,"stock_query","take_profit","stop_loss");String query=text(args,"stock_query");String normalized=normalize(m.text);
                    if(readOnly(m.text)||!normalized.contains(normalize(query))||normalized.matches(".*(取消|关闭|停用|停止).*"))throw new IllegalArgumentException("请在本条明确指定股票和个人止盈/止损价格");
                    if(normalized.matches(".*(区间|百分|%|％).*|.*[0-9]\\s*[-~～至到]\\s*[0-9].*"))throw new IllegalArgumentException("首版只接受明确的单个人民币价格，不接受区间或百分比");
                    java.math.BigDecimal take=explicitPrice(m.text,args,"take_profit","止盈"),stop=explicitPrice(m.text,args,"stop_loss","止损");
                    String symbol=resolve(query);if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    yield personal.view(personal.set(group,m.userId,symbol,take,stop));
                }
                case "list_my_price_alerts" -> {
                    fields(args,"stock_query","offset");var offset=args.path("offset");if(!offset.isMissingNode()&&(!offset.isIntegralNumber()||!offset.canConvertToInt()))throw new IllegalArgumentException("无效分页");
                    String symbol=args.has("stock_query")?resolve(text(args,"stock_query")):null;
                    yield Map.of("alerts",personal.mine(group,m.userId,offset.asInt(0),symbol),"ownerUserId",m.userId,"note","只包含当前提问者的个人提醒，每页最多20只；完整分钟收盘触发，同规则每天一次，改变该规则价格后重新判断；仍发本群，明确标明所属用户");
                }
                case "disable_personal_price_alert" -> {
                    fields(args,"stock_query");String query=text(args,"stock_query"),text=normalize(m.text);
                    if(readOnly(m.text)||!text.contains(normalize(query))||!text.matches(".*(取消|关闭|停用|停止).*")||!(text.contains("个人提醒")||text.contains("价位提醒")||(text.contains("止盈")&&text.contains("止损"))))throw new IllegalArgumentException("请明确说‘关闭股票名的个人价位提醒’，会同时停用本人该股止盈和止损");
                    String symbol=resolve(query);if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    yield Map.of("symbol",symbol,"status",personal.disable(group,m.userId,symbol)>0?"DISABLED":"ALREADY_DISABLED_OR_NOT_FOUND","scope","只停用当前用户，不改变其他人或全群设置");
                }
                case "get_recent_signals" -> {
                    fields(args,"stock_query");String symbol=args.has("stock_query")?resolve(text(args,"stock_query")):null;
                    var rows=new ArrayList<Map<String,Object>>();
                    for(var signal:signals.list(symbol,dates.today().minusDays(5),dates.today(),null,10,0)) {
                        var row=new LinkedHashMap<String,Object>();row.put("id",signal.id);row.put("symbol",signal.symbol);var stock=stocks.stock(signal.symbol);row.put("name",stock==null?"名称未记录":stock.name);
                        row.put("tradeDate",signal.tradeDate);row.put("signalAt",signal.signalAt);row.put("type",signal.signalType);row.put("referencePrice",signal.referencePrice);row.put("reason",signal.reason);row.put("riskNote",signal.riskNote);rows.add(row);
                    }
                    yield Map.of("signals",rows,"note","最近5个自然日已落库规则信号，最多10条；无信号不代表无风险，不含实时行情");
                }
                case "add_stock_monitor" -> {
                    fields(args,"stock_query");String query=text(args,"stock_query");String normalized=normalize(m.text);
                    if(readOnly(m.text))throw new IllegalArgumentException("查询或解释消息不允许新增关注，请单独明确说‘关注股票名或代码’");
                    if(!normalized.contains(normalize(query)) || !normalized.matches(".*(关注|监控|盯盘).*"))throw new IllegalArgumentException("新增关注必须在本条消息明确提供股票名称或代码");
                    for(String negative:List.of("取消","删除","不要","不用","停止","不再","别","不需要"))if(normalized.contains(negative))throw new IllegalArgumentException("否定或删除消息不允许新增关注");
                    String recognized=intent.getObject().stockQuery(m.text);
                    if(recognized==null || !normalize(query).equals(normalize(recognized)))throw new IllegalArgumentException("未确认单只股票新增意图，查询不会新增关注");
                    String symbol=resolve(query);if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    var result=subscriptions.addFromGroup(symbol,group,m.userId);
                    yield Map.of("status",result.status(),"symbol",result.symbol(),"name",result.name(),"followRecorded",true,"note","已记录为当前提问者关注；共有监控及既有阈值保留");
                }
                case "remove_stock_monitor" -> {
                    fields(args,"stock_query");String query=text(args,"stock_query");
                    if(!RemoveWatchIntent.explicit(m.text,query))throw new IllegalArgumentException("请单独明确说‘取消关注 股票名称或六位代码’，将影响所有群的公共监控；个人提醒保留。查询、否定、代他人或多股票消息不执行删除");
                    String symbol=resolve(query);if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");
                    var removed=subscriptions.remove(symbol);
                    yield Map.of("status",removed.status(),"symbol",removed.symbol(),"name",removed.name(),"removedFollowers",removed.removedFollowers(),"cancelledNotifications",removed.cancelledNotifications(),
                        "scope","全局公共关注及公共日线、盘中监控，所有群生效；关注人关系同时移除，个人止盈止损保留", "note","历史行情和信号保留；已经发送或正在发送的通知无法撤回。全市场选股、回踩候选推荐独立，不因取消关注而排除此股票");
                }
                case "set_my_name" -> {
                    fields(args,"name");String display=text(args,"name");if(readOnly(m.text)||display.length()>40||display.codePoints().anyMatch(cp->Character.isISOControl(cp)||"<>`*".indexOf(cp)>=0)||!m.text.contains(display)||!(m.text.contains("我叫")||m.text.contains("叫我")||m.text.contains("昵称")))throw new IllegalArgumentException("请明确说‘叫我某某’，仅设置自己的显示名称");
                    if(!writeUsed.compareAndSet(false,true))throw new IllegalArgumentException("每条消息最多执行一次写操作");mapper.alias(group,m.userId,display);yield Map.of("displayName",display,"status","SAVED","note","这是用户自设显示名，不是已核验的企业通讯录姓名");
                }
                default -> Map.of("error","UNSUPPORTED_TOOL");
            };
        }catch(IllegalArgumentException invalid){return Map.of("error","INVALID_REQUEST","message",invalid.getMessage()==null?"参数无效":invalid.getMessage());}
        catch(Exception unavailable){return Map.of("error","TOOL_UNAVAILABLE","message","工具暂不可用；不得宣称已执行成功或补造数据");}
    }
    private com.stocksentinel.trend.TrendLedger trendLedger(){var service=trendLedgers.getIfAvailable();if(service==null)throw new IllegalArgumentException("趋势跟踪模块未启用");return service;}
    private String resolve(String query) {
        var matches=stocks.search(normalize(query),20,0);var exact=matches.stream().filter(s->s.symbol.equals(query)||normalize(s.name).equals(normalize(query))).toList();var selected=exact.size()==1?exact:matches;
        if(selected.size()!=1)throw new IllegalArgumentException("股票未唯一匹配，请提供六位代码");return selected.getFirst().symbol;
    }
    private static void fields(JsonNode args,String...allowed){if(!args.isObject())throw new IllegalArgumentException("参数须为对象");var keys=Set.of(allowed);args.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw new IllegalArgumentException("不支持的参数");});}
    private static String text(JsonNode args,String name){var n=args.path(name);if(!n.isTextual()||n.asText().isBlank()||n.asText().length()>100||n.asText().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("缺少有效的"+name);return n.asText().strip();}
    private static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+","");}
    private static boolean readOnly(String text){for(String word:List.of("查看","查询","列表","哪些","是谁","谁关注","解释","策略","为什么","是否","有没有","吗","?","？"))if(text.contains(word))return true;return false;}
    private static long positionQuantity(String message,JsonNode args,String field,String label){
        var value=args.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalArgumentException("数量必须为整数股数");
        long quantity=value.longValue();if(!literalNumber(message,label,java.math.BigDecimal.valueOf(quantity),"股"))throw new IllegalArgumentException("数量必须逐字对应本条明确给出的股数，不接受手数或百分比推算");return quantity;
    }
    private static boolean literalNumber(String message,String label,java.math.BigDecimal number,String unit){
        String source=Normalizer.normalize(message,Normalizer.Form.NFKC);
        var match=java.util.regex.Pattern.compile(label+"(?:数量|价位)?\\s*(?:设置为|设为|为|是|:|：|=)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*"+unit).matcher(source);
        int count=0;boolean same=false;while(match.find()){count++;same=new java.math.BigDecimal(match.group(1)).compareTo(number)==0;}return count==1&&same;
    }
    private static java.math.BigDecimal explicitPrice(String text,JsonNode args,String field,String word) {
        if(!args.has(field))return null;var value=args.get(field);if(!value.isNumber())throw new IllegalArgumentException("价格必须为明确数值");var price=value.decimalValue();
        String source=Normalizer.normalize(text,Normalizer.Form.NFKC);
        String number="([0-9]+(?:\\.[0-9]+)?)";
        for(String pattern:List.of(word+"(?:价格|价位|价)?\\s*(?:设置为|调整为|改为|设为|设到|改到|设置|为|是|在|到|:|=)?\\s*"+number,"(?<![0-9.])"+number+"\\s*(?:元|块)?\\s*(?:作为|为)?"+word)){
            var match=java.util.regex.Pattern.compile(pattern).matcher(source);while(match.find())if(new java.math.BigDecimal(match.group(1)).compareTo(price)==0)return price;
        }
        throw new IllegalArgumentException(word+"价格必须逐字对应本条用户消息，不允许模型猜测或取自历史对话");
    }
}
