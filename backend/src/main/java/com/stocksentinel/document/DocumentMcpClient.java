package com.stocksentinel.document;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** Fixed MCP tools only. No model invocation, remote instruction execution, redirects or write retries. */
public class DocumentMcpClient {
    public static final Set<String> TOOLS=Set.of("create_doc","smartsheet_get_sheet","smartsheet_get_fields","smartsheet_update_fields","smartsheet_add_fields","smartsheet_add_records");
    public static class Failure extends RuntimeException {
        public final String code;public final boolean uncertain;
        public Failure(String code,boolean uncertain){super(code);this.code=code;this.uncertain=uncertain;}
    }
    private final URI endpoint;private final ObjectMapper json;private final HttpClient http;private final Duration timeout;
    public DocumentMcpClient(URI endpoint,ObjectMapper json){this(endpoint,json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),Duration.ofSeconds(20));}
    /** Explicit transport injection is for loopback protocol tests; production endpoint is validated by settings. */
    public DocumentMcpClient(URI endpoint,ObjectMapper json,HttpClient http,Duration timeout){this.endpoint=endpoint;this.json=json;this.http=http;this.timeout=timeout;}
    public Session open(){
        var session=new Session();
        var init=session.rpc("initialize",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","stocksentinel-documents","version","1")),false);
        session.version=init.path("protocolVersion").asText();
        if(!Set.of("2024-11-05","2025-03-26","2025-06-18").contains(session.version))throw new Failure("MCP_PROTOCOL_UNSUPPORTED",false);
        session.notifyInitialized();
        var list=session.rpc("tools/list",Map.of(),false);
        if(list.hasNonNull("nextCursor"))throw new Failure("MCP_TOOL_LIST_PAGINATED",false);
        for(var tool:list.path("tools"))if(TOOLS.contains(tool.path("name").asText()))session.tools.add(tool.path("name").asText());
        if(!session.tools.containsAll(TOOLS))throw new Failure("MCP_REQUIRED_TOOLS_MISSING",false);
        return session;
    }
    public final class Session {
        private String sessionId,version="2025-03-26";private long sequence;
        private final Set<String> tools=new HashSet<>();
        public JsonNode call(String name,Map<String,Object> arguments) {
            if(!tools.contains(name)||!TOOLS.contains(name))throw new Failure("MCP_TOOL_NOT_ALLOWED",false);
            boolean write=!name.equals("smartsheet_get_sheet")&&!name.equals("smartsheet_get_fields");
            var result=rpc("tools/call",Map.of("name",name,"arguments",arguments),write);
            if(result.path("isError").asBoolean())throw new Failure("MCP_TOOL_FAILED",write);
            JsonNode data=result.get("structuredContent");
            if(data==null)for(var block:result.path("content"))if("text".equals(block.path("type").asText())){
                try {var parsed=json.readTree(block.path("text").asText());if(parsed!=null&&parsed.isObject()){data=parsed;break;}}catch(Exception ignored){}
            }
            if(data==null||!data.isObject())throw new Failure("MCP_TOOL_RESULT_INVALID",write);
            if(data.has("errcode")&&(!data.path("errcode").isIntegralNumber()||data.path("errcode").asInt()!=0))throw new Failure("MCP_TOOL_REJECTED",write);
            return data;
        }
        private HttpResponse<byte[]> send(Object body,boolean write) {
            try {
                var request=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type","application/json")
                        .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",version);
                if(sessionId!=null)request.header("Mcp-Session-Id",sessionId);
                var response=http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build(),info->limitedBody());
                if(response.statusCode()<200||response.statusCode()>=300)throw new Failure(response.statusCode()==401||response.statusCode()==403?"MCP_AUTH_REQUIRED":"MCP_HTTP_FAILURE",write);
                var id=response.headers().firstValue("Mcp-Session-Id").orElse(null);
                if(id!=null){if(id.length()>512||!id.matches("[!-~]+"))throw new Failure("MCP_SESSION_INVALID",write);sessionId=id;}
                return response;
            }catch(Failure safe){throw safe;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Failure("MCP_TRANSPORT_INTERRUPTED",write);}
            catch(Exception failure){throw new Failure("MCP_TRANSPORT_FAILED",write);}
        }
        private void notifyInitialized(){send(Map.of("jsonrpc","2.0","method","notifications/initialized"),false);}
        private JsonNode rpc(String method,Map<String,Object> params,boolean write) {
            long id=++sequence;
            var response=send(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params),write);
            try {
                var objects=new ArrayList<JsonNode>();
                String body=new String(response.body(),StandardCharsets.UTF_8);
                if(response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                    for(String event:body.replace("\r\n","\n").split("\n\n")){
                        var data=new StringBuilder();for(String line:event.split("\n"))if(line.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());}
                        if(!data.isEmpty())objects.add(json.readTree(data.toString()));
                    }
                } else objects.add(json.readTree(body));
                JsonNode found=null;
                for(var item:objects){var items=item!=null&&item.isArray()?item:json.createArrayNode().add(item);
                    for(var node:items)if(node.path("id").isIntegralNumber()&&node.path("id").asLong()==id){if(found!=null)throw new Failure("MCP_RESPONSE_ID_INVALID",write);found=node;}}
                if(found==null||!"2.0".equals(found.path("jsonrpc").asText()))throw new Failure("MCP_RESPONSE_ID_INVALID",write);
                if(found.has("error"))throw new Failure("MCP_RPC_REJECTED",write);
                if(!found.path("result").isObject())throw new Failure("MCP_RESULT_INVALID",write);
                return found.get("result");
            }catch(Failure safe){throw safe;}catch(Exception invalid){throw new Failure("MCP_RESPONSE_INVALID",write);}
        }
    }
    private static HttpResponse.BodySubscriber<byte[]> limitedBody() {
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;private long bytes;private boolean stopped;
            public CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
            public void onNext(List<ByteBuffer> buffers){
                if(stopped)return;for(var buffer:buffers)bytes+=buffer.remaining();
                if(bytes>1048576){stopped=true;subscription.cancel();delegate.onError(new java.io.IOException("MCP_RESPONSE_TOO_LARGE"));}
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error){if(!stopped){stopped=true;delegate.onError(error);}}
            public void onComplete(){if(!stopped){stopped=true;delegate.onComplete();}}
        };
    }
}
