package com.stocksentinel.document;

import com.stocksentinel.jobs.TradingDatePolicy;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class DocumentScheduleTest {
    private void tick(boolean enabled,String time,String calendar,DocumentArchiveService service){
        var settings=new DocumentSettings(true,enabled,"https://qyapi.weixin.qq.com/test-mcp");
        var dates=mock(TradingDatePolicy.class);when(dates.today()).thenReturn(LocalDate.of(2026,10,22));when(dates.calendarStatus(any())).thenReturn(calendar);
        var clock=Clock.fixed(OffsetDateTime.parse("2026-10-22T"+time+"+08:00").toInstant(),TradingDatePolicy.ZONE);
        new DocumentConfiguration.DocumentSchedule(settings,service,dates,clock).tick();
    }
    @Test void doesNotExportBefore1535OrOnClosedDaysOrWhenDisabled(){
        var service=mock(DocumentArchiveService.class);tick(false,"16:00:00","OPEN",service);tick(true,"15:30:00","OPEN",service);tick(true,"16:00:00","MARKET_CLOSED",service);tick(true,"16:00:00","CALENDAR_MISSING",service);tick(true,"23:05:00","OPEN",service);verifyNoInteractions(service);
    }
    @Test void openDay1535ExportsPublicSnapshotWithoutCallingSetup(){var service=mock(DocumentArchiveService.class);tick(true,"15:35:00","OPEN",service);verify(service).export(LocalDate.of(2026,10,22),false);verifyNoMoreInteractions(service);}
}
