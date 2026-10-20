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
}