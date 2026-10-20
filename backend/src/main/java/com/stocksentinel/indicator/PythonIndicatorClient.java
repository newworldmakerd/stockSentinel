package com.stocksentinel.indicator;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PythonIndicatorClient implements IndicatorClient {
    private final RestClient client;
    public PythonIndicatorClient(@Value("${market.python-url:http://127.0.0.1:8000}") String url, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        var wireMapper=mapper.copy();
        var decimals=new com.fasterxml.jackson.databind.module.SimpleModule();
        decimals.addSerializer(java.math.BigDecimal.class,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
        wireMapper.registerModule(decimals);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).messageConverters(converters->{
            for (var converter:converters) {
                if (converter instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter jackson)
                    jackson.setObjectMapper(wireMapper);
            }
        }).build();
    }
    @Override public IndicatorContracts.Output calculate(IndicatorContracts.Input input) {
        return client.post().uri("/internal/indicators/calculate").contentType(MediaType.APPLICATION_JSON)
            .body(input).retrieve().body(IndicatorContracts.Output.class);
    }
}