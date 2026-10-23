package com.stocksentinel.chips;
import com.fasterxml.jackson.databind.*;import java.time.*;
import org.springframework.web.client.*;import org.springframework.http.client.SimpleClientHttpRequestFactory;
public class ChipClient {
    private final RestClient http;
    public static class Failure extends RuntimeException {public final String code;public Failure(String code){super(code);this.code=code;}}
    public ChipClient(String url){var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(18));http=RestClient.builder().baseUrl(url).requestFactory(factory).build();}
    public JsonNode snapshot(String symbol,LocalDate date,boolean confirmed){
        try{return http.get().uri(b->b.path("/internal/chips/snapshot").queryParam("symbol",symbol).queryParam("as_of",date).queryParam("current_day_confirmed",confirmed).build()).retrieve().body(JsonNode.class);}
        catch(RestClientResponseException e){String code="CHIP_SOURCE_UNAVAILABLE";try{String detail=new ObjectMapper().readTree(e.getResponseBodyAsString()).path("detail").asText();if(java.util.Set.of("CHIP_DISABLED","CHIP_HISTORY_UNDER_120","CHIP_BASIS_MISSING","CHIP_IDENTITY_INVALID","CHIP_SERIES_INVALID","CHIP_METRICS_INVALID","CHIP_ALGORITHM_UNAVAILABLE","CHIP_CLOSE_CONFIRMATION_REQUIRED").contains(detail))code=detail;}catch(Exception ignored){}throw new Failure(code);}
        catch(Exception e){throw new Failure("CHIP_SOURCE_UNAVAILABLE");}
    }
}
