package com.stocksentinel;

import com.stocksentinel.monitoring.*;
import com.stocksentinel.personal.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanTelemetryTest {
    @Test void diagnosticsCannotMaskRuleOutcomeAndNeverPersistExceptionText(){
        var mapper=mock(ScanTelemetryMapper.class);var service=new ScanTelemetry(mapper);var now=OffsetDateTime.now();
        doThrow(new IllegalStateException("private diagnostic data")).when(mapper).insert(any());
        assertDoesNotThrow(()->service.record(1,"000731","MINUTE_FETCH","FAILED","T_RSI_HTTP_502",now,now,0,null));
        doThrow(new IllegalStateException("private")).when(mapper).link(1,2);assertDoesNotThrow(()->service.link(1,2));
        assertEquals("DATA_STALE",ScanTelemetry.category("SKIPPED","STALE_MINUTES"));
        assertEquals("DATA_INSUFFICIENT",ScanTelemetry.category("FAILED","RSI_HISTORY_INSUFFICIENT"));
        assertEquals("SOURCE_FAILURE",ScanTelemetry.category("FAILED","T_RSI_HTTP_502"));
        assertEquals("DELIVERY_UNKNOWN",ScanTelemetry.category("UNKNOWN",null));
    }
    @Test void eligibilityDistinguishesNoHoldingsUnknownCrossDayAndZeroSellable(){
        var day=LocalDate.of(2026,10,21);assertEquals("POSITION_NOT_REPORTED",TObservationService.eligibility(null,day));
        var p=new PositionMapper.Position();p.quantity=800L;p.reportedOn=day;
        assertEquals("SELLABLE_UNKNOWN",TObservationService.eligibility(p,day));p.sellableQuantity=0L;assertEquals("SELLABLE_ZERO",TObservationService.eligibility(p,day));
        p.sellableQuantity=800L;assertEquals("READY",TObservationService.eligibility(p,day));assertEquals("SELLABLE_STALE",TObservationService.eligibility(p,day.plusDays(1)));
    }
}
