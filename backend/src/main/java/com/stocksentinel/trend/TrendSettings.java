package com.stocksentinel.trend;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class TrendSettings {
    public final boolean enabled,schedule;public final BigDecimal minAmount,riskRate,stockCap,portfolioCap,initialAtr,trailingAtr;
    public TrendSettings(@Value("${trend.enabled:false}") boolean enabled,@Value("${trend.schedule-enabled:false}") boolean schedule,
        @Value("${trend.min-amount:50000000}") BigDecimal amount,@Value("${trend.risk-rate:0.005}") BigDecimal risk,
        @Value("${trend.stock-cap:0.10}") BigDecimal stock,@Value("${trend.portfolio-cap:0.80}") BigDecimal portfolio,
        @Value("${trend.initial-atr:2}") BigDecimal initial,@Value("${trend.trailing-atr:3}") BigDecimal trailing){
        if(amount.signum()<=0||risk.signum()<=0||risk.compareTo(BigDecimal.ONE)>0||stock.signum()<=0||stock.compareTo(portfolio)>0||portfolio.compareTo(BigDecimal.ONE)>0||initial.signum()<=0||trailing.signum()<=0)throw new IllegalArgumentException("INVALID_TREND_PARAMETERS");
        this.enabled=enabled;this.schedule=schedule;minAmount=amount;riskRate=risk;stockCap=stock;portfolioCap=portfolio;initialAtr=initial;trailingAtr=trailing;
    }
}
