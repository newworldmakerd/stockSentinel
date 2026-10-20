package com.stocksentinel.market;

import java.time.LocalDate;
import java.util.List;
import com.stocksentinel.market.MarketContracts.*;

public interface MarketClient {
    List<Stock> stocks();
    Daily daily(String symbol, LocalDate start, LocalDate end, Adjustment adjustment);
}