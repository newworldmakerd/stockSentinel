package com.stocksentinel.commands;

import com.stocksentinel.intraday.*;
import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.stock.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitorSubscriptionService {
    public record Result(String status,String symbol,String name) {}
    private final WatchlistService watches;private final WatchlistMapper stocks;
    private final IntradayMapper mapper;private final IntradayMonitorService monitors;
    private final CommandMapper commands;
    public MonitorSubscriptionService(WatchlistService watches,WatchlistMapper stocks,IntradayMapper mapper,IntradayMonitorService monitors,CommandMapper commands) {
        this.watches=watches;this.stocks=stocks;this.mapper=mapper;this.monitors=monitors;this.commands=commands;
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
