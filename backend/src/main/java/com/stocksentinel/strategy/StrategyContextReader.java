package com.stocksentinel.strategy;

import com.stocksentinel.strategy.engine.StrategyContext;
import com.stocksentinel.indicator.IndicatorMapper;
import com.stocksentinel.common.entity.MarketIndicatorDaily;
import com.stocksentinel.stock.WatchlistMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StrategyContextReader {
    public static class Skipped extends RuntimeException {
        public final String code;
        public Skipped(String code) { super(code); this.code=code; }
    }
    public record Prepared(StrategyContext context,StrategyMapper.HistoryState history) {}
    private final StrategyMapper mapper;
    private final IndicatorMapper indicators;
    private final WatchlistMapper stocks;
    public StrategyContextReader(StrategyMapper mapper,IndicatorMapper indicators,WatchlistMapper stocks) {
        this.mapper=mapper; this.indicators=indicators; this.stocks=stocks;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Prepared read(String symbol,LocalDate date,StrategyParameters parameters) {
        if (stocks.stock(symbol)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"STOCK_NOT_FOUND");
        var state=mapper.state(symbol,parameters.adjustment(),date);
        if (state.total<121) throw new Skipped("INSUFFICIENT_HISTORY");
        if (state.sourceCount!=1) throw new Skipped("MIXED_SOURCE");
        if (state.invalidRows>0 || state.lastFetched==null) throw new Skipped("INVALID_HISTORY");
        var bars=mapper.recent(symbol,parameters.adjustment(),date);
        if (bars.size()<6 || !date.equals(bars.getFirst().tradeDate)) throw new Skipped("MISSING_CURRENT_BAR");
        var current=bars.get(0); var previous=bars.get(1);
        if (current.source==null || current.source.isBlank()) throw new Skipped("INVALID_HISTORY");
        var values=indicators.latest(symbol,parameters.adjustment(),parameters.calculationVersion(),date);
        if (values.size()!=2 || !date.equals(values.get(0).tradeDate) || !previous.tradeDate.equals(values.get(1).tradeDate))
            throw new Skipped("MISSING_ALIGNED_INDICATORS");
        for (var value:values) {
            if (!valid(value)) throw new Skipped("INVALID_INDICATORS");
            if (value.calculatedAt==null || value.calculatedAt.isBefore(state.lastFetched)) throw new Skipped("STALE_INDICATORS");
        }
        var reference=mapper.reference(symbol,date);
        if (reference==null || reference.closePrice==null || reference.closePrice.signum()<=0)
            throw new Skipped("MISSING_UNADJUSTED_REFERENCE");
        if (!current.source.equals(reference.source)) throw new Skipped("MIXED_REFERENCE_SOURCE");
        var volume=bars.subList(1,6).stream().map(bar->bar.volume).reduce(BigDecimal.ZERO,BigDecimal::add);
        return new Prepared(new StrategyContext(current,previous,reference,values.get(0),values.get(1),volume,parameters),state);
    }
    private boolean valid(MarketIndicatorDaily value) {
        return value.ma20!=null && value.ma20.signum()>0 && value.ma60!=null && value.ma60.signum()>0
            && value.dif!=null && value.dea!=null;
    }
}