package com.stocksentinel.market;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stocksentinel.market.MarketContracts.*;

@Service
public class MarketWriter {
    private final MarketMapper mapper;
    public MarketWriter(MarketMapper mapper) { this.mapper=mapper; }
    @Transactional public int stocks(List<Stock> stocks) {
        int written=0;
        for (int start=0; start<stocks.size(); start+=500)
            written+=mapper.upsertStocks(stocks.subList(start, Math.min(start+500,stocks.size())));
        return written;
    }
    @Transactional public int bars(List<Bar> bars) {
        int written=0;
        for (int start=0; start<bars.size(); start+=500)
            written+=mapper.upsertBars(bars.subList(start, Math.min(start+500,bars.size())));
        return written;
    }
}