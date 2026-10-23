package com.stocksentinel.commands;

import com.stocksentinel.intraday.*;
import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.stock.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitorSubscriptionService {
    public record Removal(String status,String symbol,String name,int removedFollowers,int cancelledNotifications) {}
    public record Result(String status,String symbol,String name) {}
    private final WatchlistService watches;private final WatchlistMapper stocks;
    private final IntradayMapper mapper;private final IntradayMonitorService monitors;
    private final CommandMapper commands;
    private final GroupAssistantMapper owners;
    public MonitorSubscriptionService(WatchlistService watches,WatchlistMapper stocks,IntradayMapper mapper,IntradayMonitorService monitors,CommandMapper commands,GroupAssistantMapper owners) {
        this.watches=watches;this.stocks=stocks;this.mapper=mapper;this.monitors=monitors;this.commands=commands;this.owners=owners;
    }
    @Transactional public Result addFromGroup(String symbol,String group,String user) {
        if(group==null || !group.matches("[a-f0-9]{64}") || user==null || user.isBlank() || user.length()>256 || user.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid callback identity");
        var result=add(symbol);owners.follow(group,user,symbol);return result;
    }
    /** Explicit global removal; personal prices and all historical market/signal data remain. */
    @Transactional public Removal remove(String symbol) {
        WatchlistService.symbol(symbol);commands.lockSymbol(symbol);
        var stock=stocks.stock(symbol);if(stock==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"STOCK_NOT_FOUND");
        mapper.lock(symbol);int monitor=commands.disablePublicMonitor(symbol);
        int removed=stocks.delete(symbol);int followers=commands.removePublicOwners(symbol);int cancelled=commands.cancelPendingPublic(symbol);
        return new Removal(removed>0||monitor>0||followers>0?"REMOVED":"ALREADY_REMOVED",symbol,stock.name,followers,cancelled);
    }
    /** One narrow tool action, no price changes, removals, orders or caller-supplied URLs. */
    @Transactional public Result add(String symbol) {
        WatchlistService.symbol(symbol);commands.lockSymbol(symbol);
        var before=stocks.find(symbol);var old=mapper.config(symbol);
        boolean existing=before!=null && Boolean.TRUE.equals(before.enabled) && old!=null && Boolean.TRUE.equals(old.enabled);
        var watched=watches.add(symbol,"群指令或本地API添加");
        if(!Boolean.TRUE.equals(watched.enabled)) watches.update(symbol,true,watched.remark);
        Parameters p=old==null ? new Parameters(true,new BigDecimal("2"),new BigDecimal("1.5"),15,null,null)
            : new Parameters(true,old.rapidPct,old.volumeMultiplier,old.cooldownMinutes,old.takeProfit,old.stopLoss);
        monitors.configure(symbol,p);
        return new Result(existing ? "ALREADY_MONITORED" : "ADDED",symbol,stocks.stock(symbol).name);
    }
}
