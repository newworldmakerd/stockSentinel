package com.stocksentinel.intraday;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record Ma60RiskSettings(@Value("${intraday.ma60-enabled:false}") boolean enabled,
        @Value("${intraday.ma60-cooldown-minutes:15}") int cooldownMinutes) {
    public Ma60RiskSettings {if(cooldownMinutes<1||cooldownMinutes>1440)throw new IllegalArgumentException("Invalid MA60 cooldown");}
}
