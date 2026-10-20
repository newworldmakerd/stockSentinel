package com.stocksentinel.strategy.engine;

import com.stocksentinel.common.entity.MarketDailyBar;
import com.stocksentinel.common.entity.MarketIndicatorDaily;
import com.stocksentinel.strategy.StrategyParameters;
import java.math.BigDecimal;

public record StrategyContext(MarketDailyBar currentBar,MarketDailyBar previousBar,MarketDailyBar referenceBar,
        MarketIndicatorDaily current,MarketIndicatorDaily previous,BigDecimal previousFiveVolumeSum,
        StrategyParameters parameters) {}