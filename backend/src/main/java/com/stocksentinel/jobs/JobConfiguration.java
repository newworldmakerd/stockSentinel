package com.stocksentinel.jobs;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods=false)
public class JobConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock businessClock() { return Clock.system(TradingDatePolicy.ZONE); }
}
