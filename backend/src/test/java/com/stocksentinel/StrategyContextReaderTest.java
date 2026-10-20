package com.stocksentinel;

import com.stocksentinel.strategy.*;
import com.stocksentinel.indicator.IndicatorMapper;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.common.entity.StockInfo;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.stocksentinel.StrategyFixtures.*;

class StrategyContextReaderTest {
    @Test void staleIndicatorsAreSkippedBeforeSignalEvaluation() {
        var context=buy(); var mapper=mock(StrategyMapper.class); var indicators=mock(IndicatorMapper.class); var stocks=mock(WatchlistMapper.class);
        when(stocks.stock(anyString())).thenReturn(new StockInfo());
        var state=new StrategyMapper.HistoryState(); state.total=121; state.sourceCount=1; state.lastFetched=context.current().calculatedAt.plusSeconds(1);
        when(mapper.state(anyString(),any(),any())).thenReturn(state);
        var bars=new ArrayList<com.stocksentinel.common.entity.MarketDailyBar>(); bars.add(context.currentBar()); bars.add(context.previousBar());
        for (int i=2;i<6;i++) bars.add(bar(context.currentBar().tradeDate.minusDays(i),"100","100","QFQ"));
        when(mapper.recent(anyString(),any(),any())).thenReturn(bars);
        when(indicators.latest(anyString(),any(),anyString(),any())).thenReturn(List.of(context.current(),context.previous()));
        var reader=new StrategyContextReader(mapper,indicators,stocks);
        assertEquals("STALE_INDICATORS",assertThrows(StrategyContextReader.Skipped.class,
            ()->reader.read("999997",context.currentBar().tradeDate,context.parameters())).code);
    }
    @Test void fewerThan121RowsCannotProvideTwoWarmIndicators() {
        var context=buy(); var mapper=mock(StrategyMapper.class); var stocks=mock(WatchlistMapper.class);
        when(stocks.stock(anyString())).thenReturn(new StockInfo());
        var state=new StrategyMapper.HistoryState(); state.total=120;
        when(mapper.state(anyString(),any(),any())).thenReturn(state);
        var indicators=mock(IndicatorMapper.class);
        var reader=new StrategyContextReader(mapper,indicators,stocks);
        assertEquals("INSUFFICIENT_HISTORY",assertThrows(StrategyContextReader.Skipped.class,
            ()->reader.read("999997",context.currentBar().tradeDate,context.parameters())).code);
        verifyNoInteractions(indicators);
    }
}