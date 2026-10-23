package com.stocksentinel;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.document.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loopback Streamable HTTP, never contacts WeCom. */
class DocumentMcpClientTest {
    final ObjectMapper json=new ObjectMapper();HttpServer server;ExecutorService executor;
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();final List<String> sessions=new CopyOnWriteArrayList<>();
    String mode="JSON";
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/mcp",exchange->{try{handle(exchange);}catch(Exception failed){exchange.close();}});server.start();
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    DocumentMcpClient client(){return new DocumentMcpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp?test-key=must-not-leak"),json,HttpClient.newHttpClient(),Duration.ofMillis(700));}
    void handle(HttpExchange exchange)throws Exception {
        var body=json.readTree(exchange.getRequestBody());requests.add(body);sessions.add(Objects.toString(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),""));
        if("AUTH".equals(mode)){exchange.sendResponseHeaders(401,-1);exchange.close();return;}
        String method=body.path("method").asText();if(method.equals("notifications/initialized")){exchange.sendResponseHeaders(202,-1);exchange.close();return;}
        JsonNode result;
        if(method.equals("initialize")){exchange.getResponseHeaders().set("Mcp-Session-Id","mock-session");result=json.valueToTree(Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","local-simulation","version","1")));}
        else if(method.equals("tools/list")){var names=new ArrayList<>(DocumentMcpClient.TOOLS);if(mode.equals("MISSING_TOOL"))names.remove("create_doc");result=json.valueToTree(Map.of("tools",names.stream().map(n->Map.of("name",n)).toList()));}
        else {
            if(mode.equals("TIMEOUT"))Thread.sleep(1300);
            result=mode.equals("ERROR")?json.valueToTree(Map.of("isError",true,"content",List.of(Map.of("type","text","text","provider-secret-must-not-leak"))))
                :mode.equals("TEXT")?json.valueToTree(Map.of("content",List.of(Map.of("type","text","text","{\"errcode\":0,\"docid\":\"test-document\"}"))))
                :json.valueToTree(Map.of("structuredContent",Map.of("errcode",0,"docid","test-document")));
        }
        long id=body.path("id").asLong();if(mode.equals("WRONG_ID")&&method.equals("tools/call"))id+=10;
        String payload=json.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"result",result));
        if(mode.equals("OVERSIZED")&&method.equals("tools/call"))payload="x".repeat(1100000);
        if(mode.equals("SSE")&&method.equals("tools/call")){payload="event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: "+payload+"\n\n";exchange.getResponseHeaders().set("Content-Type","text/event-stream");}
        else exchange.getResponseHeaders().set("Content-Type","application/json");
        var bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    @Test void initializesSessionAndCallsOnlyWhitelistedTools(){
        var session=client().open();assertEquals("test-document",session.call("create_doc",Map.of("doc_type",10,"doc_name","测试")).path("docid").asText());
        assertEquals(List.of("initialize","notifications/initialized","tools/list","tools/call"),requests.stream().map(r->r.path("method").asText()).toList());
        assertEquals("mock-session",sessions.getLast());
        assertThrows(DocumentMcpClient.Failure.class,()->session.call("delete_doc",Map.of()));assertEquals(4,requests.size());
    }
    @Test void parsesSseWithoutExecutingServerMessages(){mode="SSE";assertEquals("test-document",client().open().call("create_doc",Map.of()).path("docid").asText());assertEquals(4,requests.size());}
    @Test void parsesTextJsonToolResult(){mode="TEXT";assertEquals("test-document",client().open().call("create_doc",Map.of()).path("docid").asText());}
    @Test void authorizationFailureIsSafeAndDoesNotWrite(){mode="AUTH";var error=assertThrows(DocumentMcpClient.Failure.class,()->client().open());assertEquals("MCP_AUTH_REQUIRED",error.code);assertFalse(error.uncertain);assertFalse(error.toString().contains("must-not-leak"));assertEquals(1,requests.size());}
    @Test void toolFailureDoesNotLeakProviderTextOrRetry(){mode="ERROR";var error=assertThrows(DocumentMcpClient.Failure.class,()->client().open().call("create_doc",Map.of()));assertTrue(error.uncertain);assertFalse(error.toString().contains("provider-secret"));assertEquals(4,requests.size());}
    @Test void mismatchedWriteAcknowledgementIsUncertain(){mode="WRONG_ID";assertTrue(assertThrows(DocumentMcpClient.Failure.class,()->client().open().call("create_doc",Map.of())).uncertain);assertEquals(4,requests.size());}
    @Test void missingRequiredToolPreventsCreation(){mode="MISSING_TOOL";assertEquals("MCP_REQUIRED_TOOLS_MISSING",assertThrows(DocumentMcpClient.Failure.class,()->client().open()).code);assertEquals(3,requests.size());}
    @Test void timedOutWriteIsNeverAutomaticallyRetried(){mode="TIMEOUT";var error=assertThrows(DocumentMcpClient.Failure.class,()->client().open().call("create_doc",Map.of()));assertTrue(error.uncertain);assertEquals(4,requests.size());}
    @Test void oversizedWriteResponseIsBoundedAndUncertain(){mode="OVERSIZED";assertTrue(assertThrows(DocumentMcpClient.Failure.class,()->client().open().call("create_doc",Map.of())).uncertain);assertEquals(4,requests.size());}
    @Test void endpointValidationNeverPrintsSecretUrl(){assertDoesNotThrow(()->new DocumentSettings(false,false,""));var error=assertThrows(IllegalArgumentException.class,()->new DocumentSettings(true,true,"http://127.0.0.1/test?secret=must-not-leak"));assertFalse(error.toString().contains("must-not-leak"));}
}
