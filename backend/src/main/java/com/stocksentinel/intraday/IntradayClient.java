package com.stocksentinel.intraday;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;
import com.stocksentinel.intraday.IntradayContracts.Snapshot;

@Component
public class IntradayClient {
    private final RestClient client;
    private final int timeoutSeconds;
    public IntradayClient(@Value("${market.python-url:http://127.0.0.1:8000}") String url,
            @Value("${market.minute-timeout-seconds:25}") int timeout) {
        if(timeout<5 || timeout>90) throw new IllegalArgumentException("Invalid minute timeout");
        this.timeoutSeconds=timeout;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(5));factory.setReadTimeout(Duration.ofSeconds(timeout));
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public Snapshot minutes(String symbol) {
        return minutesUsing(symbol,client);
    }
    public Snapshot minutes(String symbol,int budgetSeconds){return minutesUsing(symbol,budgeted(budgetSeconds));}
    private RestClient budgeted(int seconds){
        if(seconds<1)throw new MinuteFetchException("SCAN_BUDGET_EXHAUSTED");
        long total=Math.min(seconds,timeoutSeconds)*1000L,connect=Math.min(5000,total/2);
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofMillis(connect));factory.setReadTimeout(Duration.ofMillis(total-connect));
        return client.mutate().requestFactory(factory).build();
    }
    private Snapshot minutesUsing(String symbol,RestClient http) {
        try {
            return http.get().uri(b->b.path("/internal/market/minutes").queryParam("symbol",symbol).build()).retrieve().body(Snapshot.class);
        } catch(RestClientResponseException failure) {
            if(failure.getStatusCode().value()==502) {
                var headers=failure.getResponseHeaders();
                String primary=headers==null?null:headers.getFirst("X-Minute-Primary-Error");
                String backup=headers==null?null:headers.getFirst("X-Minute-Backup-Error");
                if(safe(primary) && safe(backup))throw new MinuteFetchException("MINUTE_PRIMARY_"+primary+"_BACKUP_"+backup);
            }
            throw new MinuteFetchException("MINUTE_HTTP_"+failure.getStatusCode().value());
        } catch(ResourceAccessException failure) {
            Throwable cause=failure;
            while(cause.getCause()!=null && cause.getCause()!=cause)cause=cause.getCause();
            throw new MinuteFetchException(cause instanceof java.net.SocketTimeoutException?"MINUTE_HTTP_TIMEOUT":"MINUTE_HTTP_CONNECTION_FAILED");
        } catch(org.springframework.web.client.RestClientException failure) {
            throw new MinuteFetchException("MINUTE_RESPONSE_INVALID");
        }
    }
    public com.stocksentinel.personal.TObservationRules.Rsi tRsi(String symbol) {
        return tRsiUsing(symbol,client);
    }
    public com.stocksentinel.personal.TObservationRules.Rsi tRsi(String symbol,int budgetSeconds){return tRsiUsing(symbol,budgeted(budgetSeconds));}
    private com.stocksentinel.personal.TObservationRules.Rsi tRsiUsing(String symbol,RestClient http){
        try{return http.get().uri(b->b.path("/internal/indicators/t-rsi").queryParam("symbol",symbol).build()).retrieve().body(com.stocksentinel.personal.TObservationRules.Rsi.class);}
        catch(RestClientResponseException failure){
            var headers=failure.getResponseHeaders();String primary=headers==null?null:headers.getFirst("X-RSI-Primary-Error"),backup=headers==null?null:headers.getFirst("X-RSI-Backup-Error");
            if(safeRsi(primary)&&safeRsi(backup))throw new MinuteFetchException("T_RSI_P_"+primary+"_B_"+backup);
            throw new MinuteFetchException("T_RSI_HTTP_"+failure.getStatusCode().value());}
        catch(ResourceAccessException failure){throw new MinuteFetchException("T_RSI_CONNECTION_OR_TIMEOUT");}
        catch(org.springframework.web.client.RestClientException failure){throw new MinuteFetchException("T_RSI_RESPONSE_INVALID");}
    }
    private static boolean safeRsi(String code){return code!=null&&(safe(code)||java.util.Set.of("NOT_ATTEMPTED","CIRCUIT_OPEN","BUDGET_EXHAUSTED","FACTOR_MISSING","FACTOR_INVALID","FACTOR_ANCHOR_INVALID","RSI_HISTORY_INSUFFICIENT","RSI_INVALID_PRICE","RSI_INVALID_TIME","RSI_INVALID_SESSION","RSI_STALE_HISTORY","RSI_FUTURE_HISTORY").contains(code));}
    private static boolean safe(String code) {
        return code!=null && java.util.Set.of("TIMEOUT","CONNECTION","HTTP_400","HTTP_401","HTTP_403","HTTP_404",
            "HTTP_408","HTTP_429","HTTP_500","HTTP_502","HTTP_503","HTTP_504","HTTP_ERROR","INVALID_DATA",
            "TRANSPORT","INTERNAL","DISABLED","DATA_TIME_PARSE","DATA_TIME_DATE","DATA_TIME_PRECISION","DATA_TIME_SESSION",
            "DATA_TIME_FUTURE","DATA_TIME_ORDER","DATA_PRICE","DATA_OHLC","DATA_VOLUME","DATA_AMOUNT","DATA_NUMBER",
            "DATA_FIELD_MISSING","DATA_ROWS","DATA_EMPTY","DATA_STALE","DATA_IDENTITY","DATA_SHAPE","DATA_JSON").contains(code);
    }
}
