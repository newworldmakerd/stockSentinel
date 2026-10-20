package com.stocksentinel.indicator;

public interface IndicatorClient {
    IndicatorContracts.Output calculate(IndicatorContracts.Input input);
}