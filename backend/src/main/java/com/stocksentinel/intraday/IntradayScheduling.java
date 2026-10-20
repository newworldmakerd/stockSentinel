package com.stocksentinel.intraday;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
public class IntradayScheduling {
    // Keep existing tasks on their original single thread; minute scans have their own thread.
    @Bean(name="taskScheduler") @Primary ThreadPoolTaskScheduler existingTasks() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("stock-tasks-");return scheduler;
    }
    @Bean(name="intradayTaskScheduler") ThreadPoolTaskScheduler intradayTasks() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("intraday-");return scheduler;
    }
}
