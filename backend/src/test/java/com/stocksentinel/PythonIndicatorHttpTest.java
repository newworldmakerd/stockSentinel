package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stocksentinel.indicator.*;
import com.stocksentinel.market.MarketContracts.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PythonIndicatorHttpTest {
    @Test void requestUsesIsoDateAndExplicitFinalFlag() throws Exception {
        var request=new AtomicReference<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/indicators/calculate",exchange->{
            request.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] body="{\"symbol\":\"999998\",\"adjustment\":\"QFQ\",\"calculation_version\":\"ma-macd-vol-v1\",\"history_size\":120,\"warmup_period\":120,\"indicators\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client=new PythonIndicatorClient("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
            var price=new BigDecimal("1234567890123.0001");
            var bar=new Bar("999998",LocalDate.of(2025,1,1),price,price,price,
                price,BigDecimal.ONE,null,null,Adjustment.QFQ,"fixture",true);
            client.calculate(new IndicatorContracts.Input("999998",Adjustment.QFQ,IndicatorContracts.VERSION,List.of(bar)));
            JsonNode day=request.get().at("/bars/0/trade_date");
            assertTrue(day.isTextual(),"Date must be ISO text; received: "+day);
            assertEquals("2025-01-01",day.asText());
            assertTrue(request.get().at("/bars/0/is_final").asBoolean());
            assertTrue(request.get().at("/bars/0/close_price").isTextual());
            assertEquals("1234567890123.0001",request.get().at("/bars/0/close_price").asText());
            assertEquals(IndicatorContracts.VERSION,request.get().path("calculation_version").asText());
        } finally { server.stop(0); }
    }
}