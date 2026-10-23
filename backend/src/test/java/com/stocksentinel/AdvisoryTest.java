package com.stocksentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.advisory.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdvisoryTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<AdvisoryClient> provider(AdvisoryClient client) {
        var provider = (ObjectProvider<AdvisoryClient>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
    @Test void disabledDoesNotReadDatabaseOrCallModel() {
        var store = mock(AdviceStore.class);
        var service = new SignalAdvisoryService(provider(null), store, new ObjectMapper(), "");
        assertEquals(SignalAdvisoryService.Status.DISABLED, service.analyze(1));
        verifyNoInteractions(store);
    }
    @Test void failureKeepsOriginalSignalAndRecordsSanitizedError() throws Exception {
        var client = mock(AdvisoryClient.class);
        var store = mock(AdviceStore.class);
        when(store.loadVerifiedSignal(1)).thenReturn("{\"signal\":{\"id\":1}}");
        when(client.analyze(anyString())).thenThrow(new RuntimeException("secret-provider-error"));
        var service = new SignalAdvisoryService(provider(client), store, new ObjectMapper(), "test");
        assertEquals(SignalAdvisoryService.Status.FAILED, service.analyze(1));
        verify(store).save(eq(1L), eq("test"), anyString(), eq("FAILED"), anyString(), isNull(), eq("MODEL_ANALYSIS_FAILED"));
    }
    @Test void existingAdviceIsNotBilledAgain() throws Exception {
        var client = mock(AdvisoryClient.class);
        var store = mock(AdviceStore.class);
        when(store.existing(eq(1L), anyString(), anyString())).thenReturn(1);
        var service = new SignalAdvisoryService(provider(client), store, new ObjectMapper(), "test");
        assertEquals(SignalAdvisoryService.Status.ALREADY_ANALYZED, service.analyze(1));
        verifyNoInteractions(client);
    }
    @Test void missingSignalDoesNotCallModel() {
        var client = mock(AdvisoryClient.class);
        var service = new SignalAdvisoryService(provider(client), mock(AdviceStore.class), new ObjectMapper(), "test");
        assertEquals(SignalAdvisoryService.Status.NOT_FOUND, service.analyze(1));
        verifyNoInteractions(client);
    }
    @Test void savesStructuredAdvice() throws Exception {
        var client = mock(AdvisoryClient.class);
        var store = mock(AdviceStore.class);
        when(store.loadVerifiedSignal(1)).thenReturn("{}");
        when(client.analyze("{}")).thenReturn(new Advice("等待确认", List.of("规则命中"), List.of("数据有限"), "规则失效"));
        var service = new SignalAdvisoryService(provider(client), store, new ObjectMapper(), "test");
        assertEquals(SignalAdvisoryService.Status.SUCCESS, service.analyze(1));
        verify(store).save(eq(1L),eq("test"),anyString(),eq("SUCCESS"),eq("{}"),contains("suggestion"),isNull());
    }
    @Test void invalidAdviceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Advice("", List.of(), List.of(), ""));
    }
    @Test void modelAndAuditReceiveShanghaiTimesWithoutChangingInstant() throws Exception {
        var client = mock(AdvisoryClient.class);
        var store = mock(AdviceStore.class);
        var json = new ObjectMapper();
        when(store.loadVerifiedSignal(21)).thenReturn("""
            {"signal":{"signal_at":"2026-10-22T09:35:00+08:00","trade_date":"2026-10-22",
            "details":{"snapshot":{"bars":[{"bar_at":"2026-10-22T01:35:00Z","close_price":20.3},
            {"bar_at":"2026-10-21T16:01:00Z"}],"observed_at":"2026-10-22T01:36:12.123Z"}}}}
            """);
        when(client.analyze(anyString())).thenReturn(new Advice("等待",List.of("规则"),List.of("延迟"),"失效"));
        var service = new SignalAdvisoryService(provider(client),store,json,"test");
        assertEquals(SignalAdvisoryService.Status.SUCCESS,service.analyze(21));
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).analyze(captured.capture());
        var input=json.readTree(captured.getValue());
        assertEquals("2026-10-22T09:35+08:00",input.at("/signal/details/snapshot/bars/0/bar_at").asText());
        assertEquals("2026-10-22T00:01+08:00",input.at("/signal/details/snapshot/bars/1/bar_at").asText());
        assertEquals("2026-10-22T09:36:12.123+08:00",input.at("/signal/details/snapshot/observed_at").asText());
        assertEquals("2026-10-22",input.at("/signal/trade_date").asText());
        assertEquals(20.3,input.at("/signal/details/snapshot/bars/0/close_price").asDouble());
        verify(store).save(eq(21L),eq("test"),anyString(),eq("SUCCESS"),eq(captured.getValue()),anyString(),isNull());
    }
}
