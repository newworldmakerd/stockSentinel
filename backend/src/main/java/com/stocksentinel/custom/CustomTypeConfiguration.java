package com.stocksentinel.custom;
import org.springframework.context.annotation.*;import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
@Configuration(proxyBeanMethods=false)public class CustomTypeConfiguration {
    @Bean ConfigurationCustomizer customUuidMapping(){return configuration->configuration.getTypeHandlerRegistry().register(java.util.UUID.class,new CustomUuidTypeHandler());}
}
