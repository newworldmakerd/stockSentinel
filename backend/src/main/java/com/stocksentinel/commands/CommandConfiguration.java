package com.stocksentinel.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
public class CommandConfiguration {
    @Bean
    @ConditionalOnProperty(name="wecom.commands.enabled",havingValue="true")
    MonitorIntentClient monitorIntentClient(ObjectMapper json,
            @Value("${wecom.enabled:false}") boolean enabled,
            @Value("${llm.api-key:}") String key,
            @Value("${wecom.commands.base-url:https://api.deepseek.com}") String base,
            @Value("${wecom.commands.model:deepseek-flash}") String model) {
        URI uri=URI.create(base);
        if(!enabled || key.isBlank() || model.isBlank() || !"https".equals(uri.getScheme())
                || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Group commands require configured HTTPS model and smart bot");
        var transport=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build());
        transport.setReadTimeout(Duration.ofSeconds(30));
        return new DeepSeekMonitorIntentClient(RestClient.builder().baseUrl(base).requestFactory(transport)
            .defaultHeader("Authorization","Bearer "+key).build(),json,model);
    }
}
