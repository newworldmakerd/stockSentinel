package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.advisory.CompatibleAdvisoryClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CompatibleAdvisoryClientTest {
    @Test void sendsCompatibleRequestAndParsesJson() throws Exception {
        var mapper = new ObjectMapper();
        var builder = RestClient.builder().baseUrl("https://provider.example/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        String content = "{\"suggestion\":\"等待确认\",\"reasons\":[\"规则命中\"],\"risks\":[\"数据有限\"],\"invalidation\":\"规则失效\"}";
        var response = mapper.createObjectNode();
        var choice = response.putArray("choices").addObject();
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("content", content);
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
            .andExpect(jsonPath("$.model").value("test"))
            .andExpect(jsonPath("$.response_format.type").value("json_object"))
            .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        var advice = new CompatibleAdvisoryClient(builder.build(),mapper,"test").analyze("{}");
        assertEquals("等待确认", advice.suggestion());
        server.verify();
    }
    @Test void truncatedOutputIsRejected() {
        var builder = RestClient.builder().baseUrl("https://provider.example/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
            .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"length\"}]}", MediaType.APPLICATION_JSON));
        assertThrows(IllegalArgumentException.class, () -> new CompatibleAdvisoryClient(builder.build(),new ObjectMapper(),"test").analyze("{}"));
        server.verify();
    }
}