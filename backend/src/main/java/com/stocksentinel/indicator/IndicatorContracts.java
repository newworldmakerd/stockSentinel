package com.stocksentinel.indicator;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.stocksentinel.market.MarketContracts.Adjustment;
import com.stocksentinel.market.MarketContracts.Bar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class IndicatorContracts {
    private IndicatorContracts() {}
    public static final String VERSION="ma-macd-vol-v1";
    public static final int WARMUP=120;
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Input(String symbol, Adjustment adjustment, String calculationVersion, List<Bar> bars,boolean currentDayConfirmed) {
        public Input(String symbol,Adjustment adjustment,String calculationVersion,List<Bar> bars) {
            this(symbol,adjustment,calculationVersion,bars,false);
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Row(LocalDate tradeDate, BigDecimal ma5, BigDecimal ma10, BigDecimal ma20,
            BigDecimal ma60, BigDecimal dif, BigDecimal dea, BigDecimal macdHist, BigDecimal volumeRatio) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Output(String symbol, Adjustment adjustment, String calculationVersion, int historySize,
                         int warmupPeriod, List<Row> indicators) {}
    public record Result(String symbol, Adjustment adjustment, String calculationVersion,
                         int historySize, int written, int undefinedVolumeRatios) {}
}
