package com.stocksentinel.market;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.stocksentinel.market.MarketContracts.*;

@Service
public class MarketCollectionService {
    private final MarketClient client;
    private final MarketWriter writer;
    private final MarketMapper mapper;
    private final boolean enabled;
    private final com.stocksentinel.jobs.TradingDatePolicy dates;
    public MarketCollectionService(MarketClient client, MarketWriter writer, MarketMapper mapper,
                                   @Value("${market.enabled:false}") boolean enabled) {
        this(client,writer,mapper,enabled,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MarketCollectionService(MarketClient client, MarketWriter writer, MarketMapper mapper,
            @Value("${market.enabled:false}") boolean enabled,com.stocksentinel.jobs.TradingDatePolicy dates) {
        this.client=client; this.writer=writer; this.mapper=mapper; this.enabled=enabled;
        this.dates=dates;
    }
    private void enabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Market collection disabled");
    }
    public int syncStocks() {
        enabled();
        List<Stock> stocks;
        try {
            stocks=client.stocks();
            var seen=new HashSet<String>();
            if (stocks==null || stocks.isEmpty() || stocks.size()>20000) throw new IllegalArgumentException();
            for (var stock:stocks) if (stock==null || !seen.add(stock.symbol())) throw new IllegalArgumentException();
        } catch (Exception failure) { throw invalidUpstream(); }
        return writer.stocks(stocks);
    }
    public ImportResult collect(String symbol, LocalDate start, LocalDate end, Adjustment adjustment) {
        enabled(); validateRange(symbol,start,end);
        if (mapper.stockExists(symbol)==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Sync stock metadata first");
        Daily data;
        try {
            data=client.daily(symbol,start,end,adjustment);
            if (data==null || !symbol.equals(data.symbol()) || !Objects.equals(start,data.start())
                    || !Objects.equals(end,data.end()) || data.adjustment()!=adjustment
                    || !"SHARE".equals(data.unit()) || !"CNY".equals(data.amountUnit())
                    || data.bars()==null || data.bars().size()>3661) throw new IllegalArgumentException();
            var seen=new HashSet<LocalDate>();
            var today=dates==null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : dates.today();
            for (var bar:data.bars()) {
                if (bar==null || !symbol.equals(bar.symbol()) || bar.adjustment()!=adjustment
                        || bar.tradeDate().isBefore(start) || bar.tradeDate().isAfter(end)
                        || bar.tradeDate().isAfter(today) || !seen.add(bar.tradeDate())
                        || (bar.isFinal() && !bar.tradeDate().isBefore(today)
                            && (dates==null || !dates.confirmedSource(bar.tradeDate(),bar.source())))) throw new IllegalArgumentException();
            }
        } catch (Exception failure) { throw invalidUpstream(); }
        var finalBars=data.bars().stream().map(bar->{
            if(!bar.isFinal() && dates!=null && dates.confirmedSource(bar.tradeDate(),bar.source()))
                return new Bar(bar.symbol(),bar.tradeDate(),bar.openPrice(),bar.highPrice(),bar.lowPrice(),bar.closePrice(),
                    bar.volume(),bar.amount(),bar.turnoverRate(),bar.adjustment(),bar.source(),true);
            return bar;
        }).filter(Bar::isFinal).toList();
        var priorSources=mapper.sourceHistory(symbol,adjustment);
        if(!finalBars.isEmpty() && (finalBars.stream().anyMatch(b->"AKSHARE_TENCENT_DAILY".equals(b.source()))
                || priorSources.stream().anyMatch(b->"AKSHARE_TENCENT_DAILY".equals(b.source)))) {
            var incomingDates=finalBars.stream().map(Bar::tradeDate).collect(java.util.stream.Collectors.toSet());
            var targetSource=finalBars.getFirst().source();
            if(finalBars.stream().anyMatch(b->!targetSource.equals(b.source()))) throw invalidUpstream();
            for(var old:priorSources) {
                if(!targetSource.equals(old.source) && !incomingDates.contains(old.tradeDate))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"INCOMPLETE_SOURCE_REPLACEMENT");
            }
        }
        int written=writer.bars(finalBars);
        return new ImportResult(data.bars().size(),written,data.bars().size()-finalBars.size(),finalBars.size()>=120);
    }
    public static void validateRange(String symbol, LocalDate start, LocalDate end) {
        if (symbol==null || !symbol.matches("[0-9]{6}") || start==null || end==null || start.isAfter(end)
                || java.time.temporal.ChronoUnit.DAYS.between(start,end)>3660)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol or date range");
    }
    private static ResponseStatusException invalidUpstream() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Market provider failed or returned invalid data");
    }
}
