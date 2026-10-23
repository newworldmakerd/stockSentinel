package com.stocksentinel.pullback;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record PullbackSettings(
    @Value("${pullback.enabled:false}") boolean enabled,
    @Value("${pullback.rise-pct:15}") BigDecimal risePct,
    @Value("${pullback.min-days:2}") int minDays,
    @Value("${pullback.max-days:5}") int maxDays,
    @Value("${pullback.min-drawdown-pct:3}") BigDecimal minDrawdownPct,
    @Value("${pullback.max-drawdown-pct:8}") BigDecimal maxDrawdownPct,
    @Value("${pullback.contraction:0.70}") BigDecimal contraction,
    @Value("${pullback.minute-multiplier:1.5}") BigDecimal minuteMultiplier,
    @Value("${pullback.pool-limit:20}") int poolLimit) {
    public PullbackSettings {
        if(risePct.signum()<=0 || minDays<2 || maxDays<minDays || maxDays>20
            || minDrawdownPct.signum()<=0 || maxDrawdownPct.compareTo(minDrawdownPct)<0 || maxDrawdownPct.compareTo(new BigDecimal("100"))>=0
            || contraction.signum()<=0 || contraction.compareTo(BigDecimal.ONE)>0 || minuteMultiplier.signum()<=0 || poolLimit<1 || poolLimit>100)
            throw new IllegalArgumentException("Invalid pullback parameters");
    }
    public String version() {
        String s=risePct.stripTrailingZeros()+":"+minDays+":"+maxDays+":"+minDrawdownPct.stripTrailingZeros()+":"+maxDrawdownPct.stripTrailingZeros()+":"+contraction.stripTrailingZeros()+":"+minuteMultiplier.stripTrailingZeros();
        try {return "1.0.0-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0,16);}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
}
