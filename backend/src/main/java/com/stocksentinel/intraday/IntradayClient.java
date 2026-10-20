package com.stocksentinel.intraday;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;
import com.stocksentinel.intraday.IntradayContracts.Snapshot;

@Component
public class IntradayClient {
    private final RestClient client;
    public IntradayClient(@Value("${market.python-url:http://127.0.0.1:8000}") String url,
            @Value("${market.minute-timeout-seconds:25}") int timeout) {
        if(timeout<5 || timeout>90) throw new IllegalArgumentException("Invalid minute timeout");
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(5));factory.setReadTimeout(Duration.ofSeconds(timeout));
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public Snapshot minutes(String symbol) {
        return client.get().uri(b->b.path("/internal/market/minutes").queryParam("symbol",symbol).build()).retrieve().body(Snapshot.class);
    }
}
