package com.stocksentinel;

import com.stocksentinel.indicator.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IndicatorControllerTest {
    @Test void insufficientHistoryHasExplicitErrorCode() throws Exception {
        var service=mock(IndicatorService.class);
        when(service.calculate(anyString(),any(),any())).thenThrow(new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,"INSUFFICIENT_HISTORY: required=120, received=119"));
        MockMvcBuilders.standaloneSetup(new IndicatorController(service,mock(IndicatorMapper.class))).build()
            .perform(post("/api/v1/indicators/999998/calculate?asOf=2025-12-31&adjustment=QFQ"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_HISTORY"))
            .andExpect(jsonPath("$.detail").value("INSUFFICIENT_HISTORY: required=120, received=119"));
    }
    @Test void queryPassesRequestedVersionAndAdjustment() throws Exception {
        var mapper=mock(IndicatorMapper.class);
        when(mapper.latest(anyString(),any(),anyString(),any())).thenReturn(java.util.List.of());
        MockMvcBuilders.standaloneSetup(new IndicatorController(mock(IndicatorService.class),mapper)).build()
            .perform(get("/api/v1/indicators/999998?asOf=2025-12-31&adjustment=HFQ&calculationVersion=fixture-v0"))
            .andExpect(status().isOk());
        verify(mapper).latest("999998",com.stocksentinel.market.MarketContracts.Adjustment.HFQ,"fixture-v0",java.time.LocalDate.of(2025,12,31));
    }
}