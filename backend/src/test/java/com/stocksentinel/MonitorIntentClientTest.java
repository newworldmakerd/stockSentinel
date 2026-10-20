package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.commands.DeepSeekMonitorIntentClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

class MonitorIntentClientTest {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;
    final AtomicReference<String> response=new AtomicReference<>();
    final AtomicReference<String> request=new AtomicReference<>();
    DeepSeekMonitorIntentClient client;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{
            request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        client=new DeepSeekMonitorIntentClient(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build(),json,"test-model");
    }
    @AfterEach void stop(){server.stop(0);}
    String call(String name,String args) throws Exception {
        return json.writeValueAsString(java.util.Map.of("choices",java.util.List.of(java.util.Map.of("finish_reason","tool_calls","message",
            java.util.Map.of("tool_calls",java.util.List.of(java.util.Map.of("type","function","function",java.util.Map.of("name",name,"arguments",args))))))));
    }
    @Test void oneAllowedFunctionAndNoModelSuppliedApiUrl() throws Exception {
        response.set(call("add_stock_monitor","{\"stock_query\":\"四川美丰\"}"));
        assertEquals("四川美丰",client.stockQuery("监控四川美丰"));
        var body=json.readTree(request.get());assertEquals(1,body.path("tools").size());
        assertEquals("disabled",body.at("/thinking/type").asText());
        assertEquals("监控四川美丰",body.at("/messages/1/content").asText());
    }
    @Test void noToolIsNoAction() throws Exception {
        response.set("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"不添加\"}}]}");
        assertNull(client.stockQuery("关注哪些股票？"));
    }
    @Test void unauthorizedToolsAndAdditionalArgumentsFailClosed() throws Exception {
        response.set(call("place_order","{\"stock_query\":\"600487\"}"));assertThrows(Exception.class,()->client.stockQuery("监控600487"));
        response.set(call("add_stock_monitor","{\"stock_query\":\"600487\",\"url\":\"https://example.invalid\"}"));
        assertThrows(Exception.class,()->client.stockQuery("监控600487"));
        response.set("{\"choices\":[]}");assertThrows(Exception.class,()->client.stockQuery("监控600487"));
    }
}
