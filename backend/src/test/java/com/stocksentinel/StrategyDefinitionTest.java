package com.stocksentinel;

import com.stocksentinel.strategy.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.stocksentinel.StrategyFixtures.*;

class StrategyDefinitionTest {
    @Test void parameterChangeRequiresNewVersion() throws Exception {
        var mapper=mock(StrategyMapper.class); when(mapper.locked("SWING_TREND_V1")).thenReturn(definition());
        var changed=json().readTree(PARAMS.replace("1.2","1.3"));
        var service=new StrategyDefinitionService(mapper,json());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.update("SWING_TREND_V1","1.0.0",true,changed)).getStatusCode().value());
        verify(mapper,never()).update(anyString(),anyString(),anyString(),anyBoolean());
    }
    @Test void revisionCannotBeReusedWithDifferentParameters() throws Exception {
        var mapper=mock(StrategyMapper.class); when(mapper.locked("SWING_TREND_V1")).thenReturn(definition());
        when(mapper.revision("SWING_TREND_V1","1.1.0")).thenReturn(PARAMS);
        var changed=json().readTree(PARAMS.replace("1.2","1.3"));
        var service=new StrategyDefinitionService(mapper,json());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.update("SWING_TREND_V1","1.1.0",true,changed)).getStatusCode().value());
        verify(mapper,never()).update(anyString(),anyString(),anyString(),anyBoolean());
    }
    @Test void numericEquivalentParametersDoNotChangeMeaning() {
        var service=new StrategyDefinitionService(mock(StrategyMapper.class),json());
        assertTrue(service.same(PARAMS,PARAMS.replace("1.2","1.20")));
        assertFalse(service.same(PARAMS,PARAMS.replace("1.2","1.20000001")));
    }
    @Test void undefinedFieldsAndMissingFieldsAreNotSilentlyIgnored() {
        var service=new StrategyDefinitionService(mock(StrategyMapper.class),json());
        assertThrows(ResponseStatusException.class,()->service.parameters(PARAMS.replace("}",",\"stopLoss\":0.05}")));
        assertThrows(ResponseStatusException.class,()->service.parameters("{}"));
    }
}