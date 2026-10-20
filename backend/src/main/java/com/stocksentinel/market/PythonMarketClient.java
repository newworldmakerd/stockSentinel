package com.stocksentinel.market;

import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.stocksentinel.market.MarketContracts.*;

@Component
public class PythonMarketClient implements MarketClient {
    private final RestClient client;
    public PythonMarketClient(@Value("${market.python-url:http://127.0.0.1:8000}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(90));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    @Override public List<Stock> stocks() {
        return client.get().uri("/internal/stocks").retrieve().body(new ParameterizedTypeReference<>() {});
    }
    @Override public Daily daily(String symbol, LocalDate start, LocalDate end, Adjustment adjustment) {
        return client.get().uri(uri -> uri.path("/internal/market/daily").queryParam("symbol",symbol)
                .queryParam("start",start).queryParam("end",end).queryParam("adjustment",adjustment).build())
                .retrieve().body(Daily.class);
    }
}