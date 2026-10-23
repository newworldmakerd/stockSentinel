package com.stocksentinel.trend;
import com.fasterxml.jackson.databind.*;
import com.stocksentinel.market.MarketContracts.Bar;
import java.time.*;import java.util.*;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
public class TrendClient {
    private final RestClient http,indexHttp;
    public TrendClient(String url){var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(3));f.setReadTimeout(Duration.ofSeconds(12));
        var wire=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var decimals=new com.fasterxml.jackson.databind.module.SimpleModule();decimals.addSerializer(java.math.BigDecimal.class,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);wire.registerModule(decimals);
        var builder=RestClient.builder().baseUrl(url).requestFactory(f).messageConverters(converters->{for(var converter:converters)if(converter instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter jackson)jackson.setObjectMapper(wire);});
        http=builder.build();var indexFactory=new SimpleClientHttpRequestFactory();indexFactory.setConnectTimeout(Duration.ofSeconds(3));indexFactory.setReadTimeout(Duration.ofSeconds(25));indexHttp=builder.clone().requestFactory(indexFactory).build();}
    public JsonNode calculate(String symbol,List<Bar> adjusted,List<Bar> raw,boolean confirmed){try{return http.post().uri("/internal/trend/calculate").body(Map.of("symbol",symbol,"adjusted",adjusted,"raw",raw,"current_day_confirmed",confirmed)).retrieve().body(JsonNode.class);}catch(Exception e){throw new IllegalStateException("TREND_CALCULATION_UNAVAILABLE");}}
    public JsonNode index(LocalDate day,boolean confirmed){return index(day,confirmed,"AUTO");}
    public JsonNode index(LocalDate day,boolean confirmed,String source){if(!Set.of("AUTO","EASTMONEY","TENCENT").contains(source))throw new IllegalArgumentException("INVALID_INDEX_SOURCE");try{return indexHttp.get().uri(b->b.path("/internal/trend/index").queryParam("as_of",day).queryParam("current_day_confirmed",confirmed).queryParam("source",source).build()).retrieve().body(JsonNode.class);}catch(Exception e){throw new IllegalStateException("TREND_INDEX_UNAVAILABLE");}}
    public JsonNode quotes(List<String> keys){try{return http.post().uri("/internal/trend/quotes").body(Map.of("symbols",keys)).retrieve().body(JsonNode.class);}catch(Exception e){throw new IllegalStateException("TREND_QUOTES_UNAVAILABLE");}}
}
