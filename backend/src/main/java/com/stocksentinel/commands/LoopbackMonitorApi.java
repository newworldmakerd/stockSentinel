package com.stocksentinel.commands;

import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;
import java.util.Map;

@Component
public class LoopbackMonitorApi implements ApplicationListener<WebServerInitializedEvent> {
    private volatile int port;
    @Override public void onApplicationEvent(WebServerInitializedEvent event) {
        if(event.getApplicationContext().getServerNamespace()==null) port=event.getWebServer().getPort();
    }
    public MonitorSubscriptionService.Result add(String symbol) {
        com.stocksentinel.stock.WatchlistService.symbol(symbol);
        if(port<1) throw new IllegalStateException("Local API not ready");
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl("http://127.0.0.1:"+port).requestFactory(factory).build()
            .post().uri("/api/v1/intraday/subscriptions").body(Map.of("symbol",symbol)).retrieve().body(MonitorSubscriptionService.Result.class);
    }
}
