package com.stocksentinel.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class NotificationConfiguration {
    @Bean
    @ConditionalOnProperty(name="wecom.enabled",havingValue="true")
    SmartBotClient smartBotClient(ObjectMapper json,NotificationSettings settings,
            org.springframework.beans.factory.ObjectProvider<com.stocksentinel.commands.GroupMonitorCommands> commands) {
        var client=new SmartBotClient(json,settings);
        client.onGroupMessage(message->commands.getObject().accept(message));
        return client;
    }
}
