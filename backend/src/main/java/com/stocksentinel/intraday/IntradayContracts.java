package com.stocksentinel.intraday;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class IntradayContracts {
    private IntradayContracts() {}
    public enum Type { RAPID_RISE,RAPID_DROP,BREAKOUT_WATCH,TAKE_PROFIT,STOP_LOSS }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Bar(OffsetDateTime barAt,BigDecimal openPrice,BigDecimal highPrice,BigDecimal lowPrice,
        BigDecimal closePrice,BigDecimal volume,BigDecimal amount) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Metrics(BigDecimal fiveMinuteReturnPct,BigDecimal previousTwentyHigh,BigDecimal previousTwentyVolumeMean) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Snapshot(String symbol,String source,String adjustment,String unit,String amountUnit,
        OffsetDateTime observedAt,List<Bar> bars,Metrics metrics) {}
    public record Parameters(Boolean enabled,BigDecimal rapidPct,BigDecimal volumeMultiplier,Integer cooldownMinutes,
        BigDecimal takeProfit,BigDecimal stopLoss) {}
    public static class Config {
        public String symbol;public Boolean enabled;public BigDecimal rapidPct,volumeMultiplier,takeProfit,stopLoss;
        public Integer cooldownMinutes;public Long revision;public OffsetDateTime updatedAt;
        public Parameters parameters() { return new Parameters(enabled,rapidPct,volumeMultiplier,cooldownMinutes,takeProfit,stopLoss); }
    }
    public static class Cursor { public OffsetDateTime lastBarAt;public Long configRevision; }
    public static class State { public Boolean active;public Long configRevision;public OffsetDateTime lastTriggerAt; }
}
