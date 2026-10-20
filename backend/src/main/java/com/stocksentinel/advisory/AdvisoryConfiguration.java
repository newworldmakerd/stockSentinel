package com.stocksentinel.advisory;

import java.net.URI;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AdvisoryConfiguration {
    @Bean
    @ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
    AdvisoryClient advisoryClient(@Value("${llm.base-url}") String url,
            @Value("${llm.api-key}") String key, @Value("${llm.model}") String model,
            @Value("${llm.timeout-seconds:30}") int timeout, ObjectMapper mapper) {
        if (url.isBlank() || key.isBlank() || model.isBlank() || timeout < 1 || timeout > 120) {
            throw new IllegalArgumentException("LLM settings must be provided when enabled");
        }
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("LLM base URL must be HTTPS without credentials or query");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        var client = RestClient.builder().baseUrl(url.replaceAll("/+$", ""))
                .defaultHeader("Authorization", "Bearer " + key).requestFactory(factory).build();
        return new CompatibleAdvisoryClient(client, mapper, model);
    }
}