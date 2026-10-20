package com.stocksentinel.strategy;

import java.math.BigDecimal;
import com.stocksentinel.market.MarketContracts.Adjustment;
import com.stocksentinel.indicator.IndicatorContracts;

public record StrategyParameters(BigDecimal volumeMultiplier,Adjustment adjustment,String calculationVersion,
                                 String riskMode,String sellMode) {
    public StrategyParameters {
        if (volumeMultiplier==null || volumeMultiplier.signum()<=0 || volumeMultiplier.precision()>18 || volumeMultiplier.scale()>8 || volumeMultiplier.scale() < -18
                || adjustment==null || !IndicatorContracts.VERSION.equals(calculationVersion)
                || !"CROSS_BELOW_MA60".equals(riskMode) || !"DEAD_CROSS_BELOW_MA20".equals(sellMode))
            throw new IllegalArgumentException("Unsupported strategy parameters");
    }
}