package com.stocksentinel;

import com.stocksentinel.commands.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupAssistantClientTest {
    final ObjectMapper json=new ObjectMapper();HttpServer server;DeepSeekGroupAssistantClient client;
    final Queue<String> replies=new java.util.concurrent.ConcurrentLinkedQueue<>();
    final List<JsonNode> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{requests.add(json.readTree(e.getRequestBody()));String r=replies.poll();byte[] b=(r==null?"{}":r).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        client=new DeepSeekGroupAssistantClient(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build(),json,"test-model");
    }
    @AfterEach void stop(){server.stop(0);}
    String call(String tool,String args)throws Exception{return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","tool_calls","message",Map.of("role","assistant","tool_calls",List.of(Map.of("id","fixture-id","type","function","function",Map.of("name",tool,"arguments",args))))))));}
    String answer(String text)throws Exception{return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("role","assistant","content",text)))));}
    @Test void realHttpToolResultsAreFedBackForFinalAnswer()throws Exception {
        replies.add(call("list_watchlist","{\"scope\":\"mine\"}"));replies.add(answer("你关注了亨通光电600487"));var executions=new AtomicInteger();
        String reply=client.reply(List.of(Map.of("role","user","content","我的关注有哪些？")),(name,args)->{executions.incrementAndGet();assertEquals("list_watchlist",name);assertEquals("mine",args.path("scope").asText());return Map.of("symbol","600487");});
        assertTrue(reply.contains("亨通光电"));assertEquals(1,executions.get());assertEquals(27,requests.getFirst().path("tools").size());
        var last=requests.getLast().path("messages").get(3);assertEquals("tool",last.path("role").asText());assertTrue(last.path("content").asText().contains("600487"));assertEquals("fixture-id",last.path("tool_call_id").asText());
    }
    @Test void selectionExplanationToolReturnsFactsThroughRealLocalHttp()throws Exception {
        replies.add(call("get_selection_explanation","{\"stock_query\":\"山东路桥\"}"));replies.add(answer("山东路桥000498：日线依据2026-10-20，缩量条件满足，尚未盘中转强。"));
        var count=new AtomicInteger();client.reply(List.of(Map.of("role","user","content","山东路桥为什么入选？")),(name,args)->{
            count.incrementAndGet();assertEquals("get_selection_explanation",name);assertEquals("山东路桥",args.path("stock_query").asText());
            return Map.of("symbol","000498","name","山东路桥","dataDate","2026-10-20","pullback",Map.of("status","OBSERVATION_CANDIDATE","rules",List.of(Map.of("passed",true,"observedValue","0.5674","thresholdValue","0.70"))));
        });
        assertEquals(1,count.get());var tool=json.readTree(requests.getLast().at("/messages/3/content").asText());
        assertEquals("0.5674",tool.at("/pullback/rules/0/observedValue").asText());assertEquals("2026-10-20",tool.path("dataDate").asText());
    }
    @Test void greetingDoesNotExecuteTools()throws Exception{replies.add(answer("你好，我可以查询关注和解释策略。"));client.reply(List.of(Map.of("role","user","content","你好")),(n,a)->{fail("No tool expected");return null;});}
    @Test void truncatedOrMalformedResponsesAreRejected()throws Exception {
        replies.add("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{}}]}");assertThrows(Exception.class,()->client.reply(List.of(),(n,a)->Map.of()));
        replies.add(call("list_watchlist","[]"));assertThrows(Exception.class,()->client.reply(List.of(),(n,a)->Map.of()));
    }
    @Test void repeatedToolLoopIsBounded()throws Exception{for(int i=0;i<5;i++)replies.add(call("get_strategy_info","{}"));assertThrows(Exception.class,()->client.reply(List.of(),(n,a)->Map.of()));assertEquals(5,requests.size());assertEquals("none",requests.getLast().path("tool_choice").asText());}
}
