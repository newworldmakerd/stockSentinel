package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.strategy.*;
import com.stocksentinel.strategy.engine.*;
import com.stocksentinel.common.entity.*;
import com.stocksentinel.market.MarketContracts.Adjustment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

final class StrategyFixtures {
    static final String PARAMS="{\"volumeMultiplier\":1.2,\"adjustment\":\"QFQ\",\"calculationVersion\":\"ma-macd-vol-v1\",\"riskMode\":\"CROSS_BELOW_MA60\",\"sellMode\":\"DEAD_CROSS_BELOW_MA20\"}";
    static BigDecimal number(String value) { return new BigDecimal(value); }
    static StrategyDefinition definition() {
        var value=new StrategyDefinition(); value.code="SWING_TREND_V1"; value.version="1.0.0";
        value.name="fixture"; value.parameters=PARAMS; value.enabled=true; return value;
    }
    static MarketDailyBar bar(LocalDate date,String close,String volume,String adjustment) {
        var row=new MarketDailyBar(); row.symbol="999997"; row.tradeDate=date;
        row.closePrice=number(close); row.openPrice=row.closePrice;
        row.highPrice=row.closePrice.add(BigDecimal.ONE); row.lowPrice=row.closePrice.subtract(BigDecimal.ONE);
        row.volume=number(volume); row.adjustment=adjustment; row.source="fixture";
        row.fetchedAt=OffsetDateTime.parse("2025-06-01T00:00:00Z"); return row;
    }
    static MarketIndicatorDaily indicator(LocalDate date,String ma20,String ma60,String dif,String dea) {
        var row=new MarketIndicatorDaily(); row.symbol="999997"; row.tradeDate=date;
        row.ma5=row.ma10=row.ma20=number(ma20); row.ma60=number(ma60); row.dif=number(dif); row.dea=number(dea);
        row.macdHist=row.dif.subtract(row.dea).multiply(BigDecimal.valueOf(2)); row.volumeRatio=number("1.2");
        row.adjustment="QFQ"; row.calculationVersion="ma-macd-vol-v1";
        row.calculatedAt=OffsetDateTime.parse("2025-06-01T00:01:00Z"); return row;
    }
    static StrategyContext buy() {
        var date=LocalDate.of(2025,5,1);
        return new StrategyContext(bar(date,"110","120","QFQ"),bar(date.minusDays(1),"100","100","QFQ"),
            bar(date,"220","240","NONE"),indicator(date,"105","90","2","1"),
            indicator(date.minusDays(1),"104","90","0","1"),number("500"),
            new StrategyParameters(number("1.2"),Adjustment.QFQ,"ma-macd-vol-v1","CROSS_BELOW_MA60","DEAD_CROSS_BELOW_MA20"));
    }
    static ObjectMapper json() { return new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
}