package com.stocksentinel;

import com.stocksentinel.jobs.*;
import com.stocksentinel.common.entity.MarketCalendar;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TradingDatePolicyTest {
    private final LocalDate day=LocalDate.of(2026,10,20);
    private TradingDatePolicy policy(JobMapper mapper,int hour,int minute) {
        return new TradingDatePolicy(mapper,Clock.fixed(day.atTime(hour,minute).atZone(TradingDatePolicy.ZONE).toInstant(),ZoneOffset.UTC));
    }
    private void open(JobMapper mapper) {
        var row=new MarketCalendar();row.exchange="CN";row.isOpen=true;when(mapper.calendar(day)).thenReturn(row);
    }
    @Test void noWeekdayFallbackWhenCalendarMissing() {
        var mapper=mock(JobMapper.class);assertEquals("CALENDAR_MISSING",policy(mapper,16,0).calendarStatus(day));
    }
    @Test void confirmationIsRejectedBefore1500EvenWithOpenCalendar() {
        var mapper=mock(JobMapper.class);open(mapper);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->policy(mapper,14,59).confirm(day,"JOB_FIXTURE"));
        verify(mapper,never()).confirm(any(),anyString());
    }
    @Test void todayRequiresConfirmationAndExactSource() {
        var mapper=mock(JobMapper.class);open(mapper);var dates=policy(mapper,15,15);
        assertFalse(dates.allowToday(day));when(mapper.anyConfirmation(day)).thenReturn(1);
        assertTrue(dates.allowToday(day));assertFalse(dates.confirmedSource(day,"OTHER"));
        when(mapper.confirmed(day,"JOB_FIXTURE")).thenReturn(1);assertTrue(dates.confirmedSource(day,"JOB_FIXTURE"));
        assertFalse(dates.allowToday(day.plusDays(1)));
    }
    @Test void disabledScheduleNeverInvokesJob() {
        var jobs=mock(DailyScanService.class);var dates=mock(TradingDatePolicy.class);
        new DailyScanScheduler(jobs,dates,false).closeScan();verifyNoInteractions(jobs,dates);
    }
    @Test void enabledScheduleUsesBusinessDateAndScheduledTrigger() {
        var jobs=mock(DailyScanService.class);var dates=mock(TradingDatePolicy.class);when(dates.today()).thenReturn(day);
        new DailyScanScheduler(jobs,dates,true).closeScan();verify(jobs).run(day,"SCHEDULED");
        var scheduled=java.util.Arrays.stream(DailyScanScheduler.class.getDeclaredMethods()).filter(m->m.getName().equals("closeScan")).findFirst().orElseThrow()
            .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertEquals("Asia/Shanghai",scheduled.zone());assertEquals("0 15 15 * * *",scheduled.cron());
    }
}
