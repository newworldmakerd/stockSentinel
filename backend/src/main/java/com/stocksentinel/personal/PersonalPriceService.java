package com.stocksentinel.personal;

import com.stocksentinel.stock.*;
import com.stocksentinel.commands.GroupAssistantMapper;
import com.stocksentinel.intraday.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.report.DailyReportService;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

@Service
public class PersonalPriceService {
    private final PersonalPriceMapper mapper;private final WatchlistMapper stocks;private final GroupAssistantMapper people;
    private final DailyReportService destination;private final TradingDatePolicy dates;private final Clock clock;private final boolean enabled;
    public PersonalPriceService(PersonalPriceMapper mapper,WatchlistMapper stocks,GroupAssistantMapper people,DailyReportService destination,TradingDatePolicy dates,Clock clock,@Value("${personal-price.enabled:false}") boolean enabled){this.mapper=mapper;this.stocks=stocks;this.people=people;this.destination=destination;this.dates=dates;this.clock=clock;this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    private void identity(String group,String user){if(!enabled)throw new IllegalArgumentException("个人价位提醒未启用");if(group==null||!destination.destinationHashes().contains(group)||user==null||user.isBlank()||user.length()>256||user.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("提醒身份无效");}
    @Transactional public PersonalPriceMapper.Config set(String group,String user,String symbol,BigDecimal take,BigDecimal stop) {
        identity(group,user);WatchlistService.symbol(symbol);var stock=stocks.stock(symbol);
        if(stock==null||!Set.of("SH","SZ").contains(stock.exchange))throw new IllegalArgumentException("仅支持股票目录中的沪深股票");
        if((take==null&&stop==null)||(take!=null&&!IntradayValidation.price(take))||(stop!=null&&!IntradayValidation.price(stop)))throw new IllegalArgumentException("至少提供一个有效价格，最多4位小数");
        mapper.lockKey(group+"\n"+user+"\n"+symbol);var old=mapper.lock(group,user,symbol);var row=new PersonalPriceMapper.Config();row.groupHash=group;row.userId=user;row.symbol=symbol;
        row.takeProfit=take!=null?take:old==null?null:old.takeProfit;row.stopLoss=stop!=null?stop:old==null?null:old.stopLoss;
        if(row.takeProfit!=null&&row.stopLoss!=null&&row.stopLoss.compareTo(row.takeProfit)>=0)throw new IllegalArgumentException("止损价必须低于止盈价");
        mapper.configure(row);return mapper.lock(group,user,symbol);
    }
    @Transactional public int disable(String group,String user,String symbol){identity(group,user);WatchlistService.symbol(symbol);mapper.lockKey(group+"\n"+user+"\n"+symbol);return mapper.disable(group,user,symbol);}
    public Object mine(String group,String user,int offset,String symbol){identity(group,user);if(offset<0||offset>10000)throw new IllegalArgumentException("无效分页");if(symbol!=null){var row=mapper.get(group,user,symbol);return row==null?List.of():List.of(view(row));}return mapper.mine(group,user,offset).stream().map(this::view).toList();}
    public Map<String,Object> view(PersonalPriceMapper.Config row){var result=new LinkedHashMap<String,Object>();result.put("symbol",row.symbol);var stock=stocks.stock(row.symbol);result.put("name",stock==null?"名称未记录":stock.name);result.put("enabled",row.enabled);result.put("takeProfit",row.takeProfit);result.put("stopLoss",row.stopLoss);result.put("takeProfitFiredOn",row.takeProfitFiredOn);result.put("stopLossFiredOn",row.stopLossFiredOn);result.put("revision",row.revision);result.put("scope","个人提醒，仍发本群并标明所属用户；不改变全群价位");return result;}
    public List<String> symbols(){return enabled?destination.destinationHashes().stream().flatMap(hash->mapper.symbols(hash).stream()).distinct().sorted().toList():List.of();}
    @Transactional public int evaluate(String symbol,IntradayContracts.Snapshot snapshot) {
        if(!enabled)return 0;var now=OffsetDateTime.now(clock.withZone(TradingDatePolicy.ZONE));
        if(!"OPEN".equals(dates.calendarStatus(now.toLocalDate()))||!IntradayValidation.scanTime(now.toLocalTime()))return 0;
        IntradayValidation.snapshot(symbol,snapshot,now);var bar=snapshot.bars().getLast();var day=now.toLocalDate();int created=0;
        for(String hash:destination.destinationHashes())for(var row:mapper.active(hash,symbol)) {
            if(row.lastBarAt!=null&&!bar.barAt().isAfter(row.lastBarAt))continue;
            boolean take=row.takeProfit!=null&&!day.equals(row.takeProfitFiredOn)&&bar.closePrice().compareTo(row.takeProfit)>=0;
            boolean stop=row.stopLoss!=null&&!day.equals(row.stopLossFiredOn)&&bar.closePrice().compareTo(row.stopLoss)<=0;
            if(take)created+=event(row,bar,true);if(stop)created+=event(row,bar,false);mapper.advance(row.id,bar.barAt(),day,take,stop);
        }
        return created;
    }
    private int event(PersonalPriceMapper.Config row,IntradayContracts.Bar bar,boolean take) {
        var stock=stocks.stock(row.symbol);String display=people.displayName(row.groupHash,row.userId);String owner=display==null?row.userId:display+"（企微用户ID："+row.userId+"）";
        var event=new PersonalPriceMapper.Event();event.configId=row.id;event.revision=row.revision;event.groupHash=row.groupHash;event.eventType=take?"PERSONAL_TAKE_PROFIT":"PERSONAL_STOP_LOSS";event.barAt=bar.barAt();event.referencePrice=bar.closePrice();
        event.payload=com.stocksentinel.notification.MessageIdentity.header(com.stocksentinel.notification.MessageIdentity.Kind.RISK,"个人"+(take?"止盈":"止损")+"价位提醒","个人止盈止损","个人价位","盘中到价")+"提醒所属用户："+safe(owner)+"\n股票："+safe(stock.name)+" "+row.symbol+"\n触发时间："+com.stocksentinel.notification.MessageDisplay.time(bar.barAt())+"（北京时间）"+"\n\n💰 完整分钟收盘价："+bar.closePrice()+" 元\n你的"+(take?"止盈":"止损")+"价："+(take?row.takeProfit:row.stopLoss)+" 元\n\n⚠️ 此价位仅属于上述用户，不代表全群设置、持仓或交易指令；不自动下单。";
        return mapper.event(event);
    }
    private static String safe(String text){return text.replaceAll("[\\p{Cntrl}<>`*]"," ");}
}
