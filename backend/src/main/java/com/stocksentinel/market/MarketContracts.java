package com.stocksentinel.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class MarketContracts {
    private MarketContracts() {}
    public enum Adjustment { NONE, QFQ, HFQ }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Stock(String symbol, String name, String exchange) {
        public Stock {
            require(symbol != null && symbol.matches("[0-9]{6}") && name != null && !name.isBlank()
                    && name.length() <= 100 && exchange != null && Set.of("SH","SZ","BJ").contains(exchange));
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Bar(String symbol, LocalDate tradeDate, BigDecimal openPrice, BigDecimal highPrice,
                      BigDecimal lowPrice, BigDecimal closePrice, BigDecimal volume, BigDecimal amount,
                      BigDecimal turnoverRate, Adjustment adjustment, String source, boolean isFinal) {
        public Bar {
            require(symbol != null && symbol.matches("[0-9]{6}") && tradeDate != null && adjustment != null
                    && source != null && !source.isBlank() && source.length() <= 40);
            require(positive(openPrice) && positive(highPrice) && positive(lowPrice) && positive(closePrice)
                    && volume != null && volume.signum() >= 0 && (amount == null || amount.signum() >= 0)
                    && (turnoverRate == null || turnoverRate.signum() >= 0));
            require(lowPrice.compareTo(openPrice.min(closePrice)) <= 0
                    && highPrice.compareTo(openPrice.max(closePrice)) >= 0 && lowPrice.compareTo(highPrice) <= 0);
        }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Daily(String symbol, LocalDate start, LocalDate end, Adjustment adjustment,
                        String unit, String amountUnit, List<Bar> bars) {}
    public record ImportResult(int received, int written, int skipped, boolean historySufficient) {}
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid market data");
    }
}