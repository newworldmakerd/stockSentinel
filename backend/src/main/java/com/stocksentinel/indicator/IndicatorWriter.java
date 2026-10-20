package com.stocksentinel.indicator;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stocksentinel.market.MarketContracts.Adjustment;

@Service
public class IndicatorWriter {
    private final IndicatorMapper mapper;
    public IndicatorWriter(IndicatorMapper mapper) { this.mapper=mapper; }
    @Transactional public int save(String symbol,Adjustment adjustment,String version,List<IndicatorContracts.Row> rows) {
        int written=0;
        for (int start=0;start<rows.size();start+=500)
            written+=mapper.upsert(symbol,adjustment,version,rows.subList(start,Math.min(start+500,rows.size())));
        return written;
    }
}