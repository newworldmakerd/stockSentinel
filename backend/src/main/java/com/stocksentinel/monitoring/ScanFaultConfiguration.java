package com.stocksentinel.monitoring;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration(proxyBeanMethods=false)
public class ScanFaultConfiguration {
    @Bean(name="scanFaultTaskScheduler") ThreadPoolTaskScheduler tasks(){var tasks=new ThreadPoolTaskScheduler();tasks.setPoolSize(1);tasks.setThreadNamePrefix("scan-fault-");return tasks;}
}
