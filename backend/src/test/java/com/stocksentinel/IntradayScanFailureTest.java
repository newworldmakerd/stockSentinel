package com.stocksentinel;

import com.stocksentinel.intraday.*;
import com.stocksentinel.jobs.*;
import com.stocksentinel.pullback.PullbackService;
import com.stocksentinel.personal.PersonalPriceService;
import java.time.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class IntradayScanFailureTest {
    @Test void exhaustedBudgetSkipsUnprocessedStockWithoutCallingItsProvider() throws Exception {
        var mapper=mock(IntradayMapper.class);var jobs=mock(JobMapper.class);var client=mock(IntradayClient.class);var writer=mock(IntradayWriter.class);
        var dates=mock(TradingDatePolicy.class);when(dates.calendarStatus(any())).thenReturn("OPEN");
        var source=mock(DataSource.class);var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.Statement.class);var rs=mock(java.sql.ResultSet.class);
        @SuppressWarnings("unchecked") ObjectProvider<DataSource> sources=mock(ObjectProvider.class);
        when(sources.getObject()).thenReturn(source);when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(rs);when(rs.getBoolean(1)).thenReturn(true);
        doAnswer(c->{((JobMapper.Execution)c.getArgument(0)).id=8L;return 1;}).when(mapper).start(any());
        var a=new IntradayContracts.Config();a.symbol="600487";var b=new IntradayContracts.Config();b.symbol="000731";when(mapper.monitors()).thenReturn(List.of(a,b));
        var timer=new java.util.concurrent.atomic.AtomicLong(1);var snapshot=new IntradayContracts.Snapshot(a.symbol,null,null,null,null,null,List.of(),null);
        when(client.minutes(eq(a.symbol),anyInt())).thenAnswer(c->{timer.addAndGet(60_000_000_000L);return snapshot;});when(writer.write(a,snapshot)).thenReturn(List.of(1L));
        var scans=new IntradayScanService(mapper,jobs,client,writer,dates,Clock.fixed(Instant.parse("2026-10-21T02:21:05Z"),TradingDatePolicy.ZONE),sources,true,true);
        ReflectionTestUtils.setField(scans,"scanTimer",(java.util.function.LongSupplier)timer::get);
        var pullback=mock(PullbackService.class);when(pullback.pool()).thenReturn(List.of());ReflectionTestUtils.setField(scans,"pullback",pullback);
        var personal=mock(PersonalPriceService.class);when(personal.symbols()).thenReturn(List.of());ReflectionTestUtils.setField(scans,"personal",personal);
        var result=scans.scan("MANUAL");assertEquals("PARTIAL",result.status());assertEquals(1,result.skipped());assertEquals(1,result.signals());
        verify(client,never()).minutes(eq(b.symbol),anyInt());verify(jobs).item(8L,b.symbol,"INTRADAY","SKIPPED","SCAN_BUDGET_EXHAUSTED");
    }
    @Test void failedStockDoesNotPreventNextStockAndWriteFailureHasItsOwnCode() throws Exception {
        var mapper=mock(IntradayMapper.class);var jobs=mock(JobMapper.class);
        var client=mock(IntradayClient.class);var writer=mock(IntradayWriter.class);
        var dates=mock(TradingDatePolicy.class);var pullback=mock(PullbackService.class);var personal=mock(PersonalPriceService.class);
        var source=mock(DataSource.class);var connection=mock(java.sql.Connection.class);
        var statement=mock(java.sql.Statement.class);var rs=mock(java.sql.ResultSet.class);
        @SuppressWarnings("unchecked") ObjectProvider<DataSource> sources=mock(ObjectProvider.class);
        when(sources.getObject()).thenReturn(source);when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(rs);when(rs.getBoolean(1)).thenReturn(true);
        doAnswer(call->{((JobMapper.Execution)call.getArgument(0)).id=7L;return 1;}).when(mapper).start(any());
        when(dates.calendarStatus(any())).thenReturn("OPEN");when(pullback.pool()).thenReturn(List.of());when(personal.symbols()).thenReturn(List.of());
        var a=new IntradayContracts.Config();a.symbol="600487";var b=new IntradayContracts.Config();b.symbol="000731";
        when(mapper.monitors()).thenReturn(List.of(a,b));
        var snapshot=new IntradayContracts.Snapshot(b.symbol,null,null,null,null,null,List.of(),null);
        when(client.minutes(eq(a.symbol),anyInt())).thenThrow(new MinuteFetchException("MINUTE_HTTP_TIMEOUT"));when(client.minutes(eq(b.symbol),anyInt())).thenReturn(snapshot);
        when(writer.write(b,snapshot)).thenReturn(List.of(123L));
        var scans=new IntradayScanService(mapper,jobs,client,writer,dates,Clock.fixed(Instant.parse("2026-10-21T02:21:05Z"),TradingDatePolicy.ZONE),sources,true,true);
        ReflectionTestUtils.setField(scans,"pullback",pullback);ReflectionTestUtils.setField(scans,"personal",personal);
        var telemetry=mock(com.stocksentinel.monitoring.ScanTelemetry.class);ReflectionTestUtils.setField(scans,"telemetry",telemetry);
        var result=scans.scan("MANUAL");
        assertEquals("PARTIAL",result.status());assertEquals(1,result.failed());assertEquals(1,result.signals());assertEquals(2,result.processed());
        verify(jobs).item(7L,a.symbol,"INTRADAY","FAILED","MINUTE_HTTP_TIMEOUT");
        verify(jobs).item(7L,b.symbol,"INTRADAY","SUCCESS","GENERATED_1");
        verify(telemetry).record(eq(7L),eq(a.symbol),eq("MINUTE_FETCH"),eq("FAILED"),eq("MINUTE_HTTP_TIMEOUT"),any(),any(),anyLong(),isNull());
        verify(telemetry).link(7L,123L);
        verify(writer,never()).write(eq(a),any());
        when(writer.write(b,snapshot)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("private sql data"));
        assertEquals("FAILED",scans.scan("MANUAL").status());
        verify(jobs).item(7L,b.symbol,"INTRADAY","FAILED","INTRADAY_WRITE_DATABASE_FAILED");
        var observer=mock(com.stocksentinel.monitoring.ScanFaultService.class);
        @SuppressWarnings("unchecked") ObjectProvider<com.stocksentinel.monitoring.ScanFaultService> observers=mock(ObjectProvider.class);
        when(observers.getIfAvailable()).thenReturn(observer);doThrow(new IllegalStateException("private queue failure")).when(observer).observe(7L);
        ReflectionTestUtils.setField(scans,"faults",observers);
        doReturn(List.of(124L)).when(writer).write(b,snapshot);
        assertEquals("PARTIAL",scans.scan("MANUAL").status());verify(observer).observe(7L);
    }
}
