package com.stocksentinel.trend;

import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;

/** Commit a prepared snapshot, pending-lot binding and recovery result together. */
public class TrendPreparationWriter {
    private final TrendMapper mapper;
    private final TrendLedger ledger;
    public TrendPreparationWriter(TrendMapper mapper,TrendLedger ledger){this.mapper=mapper;this.ledger=ledger;}
    @Transactional public void ready(LocalDate day,String symbol,String metrics){
        mapper.item(day,symbol,"READY",null,metrics);
        ledger.bindPending(symbol);
    }
}
