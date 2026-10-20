package com.stocksentinel;

import com.stocksentinel.market.*;
import com.stocksentinel.market.MarketContracts.*;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PythonMarketHttpTest {
    @Test void realHttpMapsPythonSnakeCaseContract() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var query=new AtomicReference<String>();
        server.createContext("/internal/market/daily", exchange->{
            query.set(exchange.getRequestURI().getQuery());
            byte[] body=("""
                {"symbol":"600487","start":"2025-01-01","end":"2025-12-31","adjustment":"QFQ",
                "unit":"SHARE","amount_unit":"CNY","bars":[{"symbol":"600487","trade_date":"2025-01-02",
                "open_price":"10","high_price":"12","low_price":"9","close_price":"11","volume":"12300",
                "amount":"135300","turnover_rate":"0.0125","adjustment":"QFQ","source":"fixture","is_final":true}]}
                """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client=new PythonMarketClient("http://127.0.0.1:"+server.getAddress().getPort());
            var result=client.daily("600487",LocalDate.of(2025,1,1),LocalDate.of(2025,12,31),Adjustment.QFQ);
            assertEquals("CNY",result.amountUnit());
            assertTrue(result.bars().getFirst().isFinal());
            assertEquals(LocalDate.of(2025,1,2),result.bars().getFirst().tradeDate());
            assertTrue(query.get().contains("adjustment=QFQ"));
        } finally { server.stop(0); }
    }
}