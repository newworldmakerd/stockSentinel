package com.stocksentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StockSentinelApplication {
    public static void main(String[] args) {
        if(java.util.Arrays.asList(args).contains("--discover-wecom-chatid")) {
            System.exit(com.stocksentinel.notification.ChatIdDiscovery.run(System.getenv(),System.out));
            return;
        }
        SpringApplication.run(StockSentinelApplication.class, args);
    }
}
