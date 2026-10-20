package com.stocksentinel;

import com.stocksentinel.stock.*;
import com.stocksentinel.strategy.*;
import com.stocksentinel.common.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.stocksentinel.StrategyFixtures.*;

class WatchlistAndStrategyApiTest {
    @Test void duplicateAddPreservesExistingWatchlistSettings() {
        var mapper=mock(WatchlistMapper.class); when(mapper.stock("600487")).thenReturn(new StockInfo());
        var existing=new StockWatchlist(); existing.symbol="600487"; existing.enabled=false; existing.remark="original";
        when(mapper.find("600487")).thenReturn(existing);
        var result=new WatchlistService(mapper).add("600487","new");
        assertFalse(result.enabled); assertEquals("original",result.remark);
        verify(mapper,never()).update(anyString(),anyBoolean(),any());
    }
    @Test void unknownStrategyUpdateFieldsAreRejectedByApi() throws Exception {
        var service=mock(StrategyDefinitionService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new StrategyController(mock(StrategyMapper.class),service,mock(StrategyEvaluationService.class),json()))
            .setControllerAdvice(new StrategyApiErrors()).build();
        mvc.perform(put("/api/v1/strategies/SWING_TREND_V1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"1.0.0\",\"enabled\":true,\"parameters\":"+PARAMS.replace("}",",\"unknown\":true}")+"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STRATEGY_UPDATE"));
        verifyNoInteractions(service);
    }
}