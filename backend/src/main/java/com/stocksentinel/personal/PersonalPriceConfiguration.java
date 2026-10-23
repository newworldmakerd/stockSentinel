package com.stocksentinel.personal;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration(proxyBeanMethods=false)
public class PersonalPriceConfiguration {
    @Bean(name="personalPriceTaskScheduler") ThreadPoolTaskScheduler tasks(){var tasks=new ThreadPoolTaskScheduler();tasks.setPoolSize(1);tasks.setThreadNamePrefix("personal-price-");return tasks;}
}
