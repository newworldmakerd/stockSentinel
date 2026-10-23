package com.stocksentinel;

import com.stocksentinel.pullback.*;
import com.stocksentinel.report.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import com.stocksentinel.notification.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PullbackDigestTest {
    final PullbackDigestMapper mapper=mock(PullbackDigestMapper.class);
    final PullbackDigestQueue queue=mock(PullbackDigestQueue.class);
    final PullbackService pool=mock(PullbackService.class);
    final ReportMapper reports=mock(ReportMapper.class);
    final DailyReportService daily=mock(DailyReportService.class);
    final TradingDatePolicy dates=mock(TradingDatePolicy.class);
    final NotificationClient client=mock(NotificationClient.class);
    final ObjectProvider<NotificationClient> clients=mock(ObjectProvider.class);
    final LocalDate date=LocalDate.of(2026,10,21);
    final OffsetDateTime now=date.atTime(9,40).atOffset(ZoneOffset.ofHours(8));
    PullbackDigestService service(LocalTime time,boolean enabled){return new PullbackDigestService(mapper,queue,PullbackRulesTest.parameters(),pool,reports,daily,new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","test-group"),clients,dates,Clock.fixed(date.atTime(time).atZone(TradingDatePolicy.ZONE).toInstant(),TradingDatePolicy.ZONE),enabled,false);}
    @BeforeEach void setup(){when(queue.enqueue(any(),anyString(),any())).thenAnswer(call->{java.util.function.Supplier<String> render=call.getArgument(2);return mapper.enqueue(call.getArgument(0),call.getArgument(1),render.get())==1;});when(dates.calendarStatus(date)).thenReturn("OPEN");when(reports.previousOpen(date)).thenReturn(date.minusDays(1));when(daily.destinationHash()).thenReturn(new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","test-group").destinationHash("test-group"));when(clients.getIfAvailable()).thenReturn(client);
        var run=new ReportMapper.Run();run.asOf=date.minusDays(1);run.total=5224;run.status="RUNNING";when(reports.run(date)).thenReturn(run);
        when(reports.coverage(date)).thenReturn(List.of(Map.of("status","NO_MATCH","count",84),Map.of("status","PENDING","count",5140)));
    }
    @Test void slotsIncludeSessionEndsButExcludeLunchAndOpening(){for(LocalTime t:List.of(LocalTime.of(9,40),LocalTime.of(11,30),LocalTime.of(13,10),LocalTime.of(15,0)))assertTrue(PullbackDigestService.session(t));for(LocalTime t:List.of(LocalTime.of(9,30),LocalTime.of(12,0),LocalTime.of(13,0),LocalTime.of(15,10)))assertFalse(PullbackDigestService.session(t));}
    @Test void disabledClosedAndLunchDoNotEnqueue(){service(LocalTime.of(10,10),false).tick();service(LocalTime.NOON,true).tick();when(dates.calendarStatus(date)).thenReturn("CLOSED");service(LocalTime.of(10,10),true).tick();verifyNoInteractions(mapper,client);}
    @Test void snapshotUsesExactSlotAndReportsPartialEmptyCoverage(){service(LocalTime.of(9,40),true).tick();verify(mapper).enqueue(eq(now),eq(new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","test-group").destinationHash("test-group")),argThat(s->s.contains("84/5224")&&s.contains("暂无符合条件")&&s.contains("阶段性结果")));verifyNoInteractions(client);}
    @Test void topFiveShowsNamesCodesAndUnconfirmedThresholds(){var candidates=new ArrayList<PullbackMapper.Candidate>();for(int i=0;i<6;i++){var c=new PullbackMapper.Candidate();c.name="测试股";c.symbol="60000"+i;c.referenceHigh=new BigDecimal("12.34");candidates.add(c);}when(pool.pool()).thenReturn(candidates);var text=service(LocalTime.of(10,10),true).render(now);assertTrue(text.contains("测试股 600000"));assertTrue(text.contains("12.34"));assertFalse(text.contains("600005"));assertTrue(text.contains("尚不等于盘中转强"));}
    @Test void missingRunDoesNotInventRecommendations(){when(reports.run(date)).thenReturn(null);assertTrue(service(LocalTime.of(10,10),true).render(now).contains("尚未就绪"));verifyNoInteractions(pool);}
    @Test void includesPreviouslyRecommendedAndStillLimitsToTopFive(){
        var candidates=new ArrayList<PullbackMapper.Candidate>();
        for(int i=0;i<7;i++){var c=new PullbackMapper.Candidate();c.name="测试股";c.symbol="60000"+i;c.referenceHigh=BigDecimal.TEN;candidates.add(c);}
        when(pool.pool()).thenReturn(candidates);when(mapper.recommended(date,new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","test-group").destinationHash("test-group"))).thenReturn(List.of("600000","600001"));
        var text=service(LocalTime.of(10,20),true).render(now.plusMinutes(10));
        assertTrue(text.contains("600000"));assertTrue(text.contains("600001"));assertFalse(text.contains("600006"));assertTrue(text.contains("5. 测试股"));
    }
    @Test void sameStockCanAppearInMorningAndEvening(){
        var c=new PullbackMapper.Candidate();c.name="山东路桥";c.symbol="000498";c.referenceHigh=BigDecimal.TEN;
        when(pool.pool()).thenReturn(List.of(c));when(mapper.recommended(date,new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","test-group").destinationHash("test-group"))).thenReturn(List.of("000498"));
        var text=service(LocalTime.of(9,40),true).render(now);assertTrue(text.contains("山东路桥"));assertTrue(text.contains("可以重复推荐"));assertTrue(service(LocalTime.of(15,30),true).render(date.atTime(15,30).atOffset(ZoneOffset.ofHours(8))).contains("山东路桥"));verify(mapper,never()).recommended(any(),anyString());
    }
    @Test void closingMinuteAllowsSchedulerAndDeliveryJitter(){assertTrue(PullbackDigestService.session(LocalTime.of(11,30,59)));assertTrue(PullbackDigestService.session(LocalTime.of(15,0,59)));assertFalse(PullbackDigestService.session(LocalTime.of(15,1)));}
    @Test void dataPreparationRetainsTradingSessionRetriesWithoutSendingMessages(){
        var screening=mock(ScreeningService.class);var service=service(LocalTime.of(10,10),true);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"screening",screening);
        when(screening.start()).thenReturn(false);service.refreshScreening();verify(screening).start();verify(screening).retry();verifyNoInteractions(queue,mapper,client);
    }
    @Test void onlyMorningAndEveningEnqueueAndEveningDeliveryIsAllowed(){
        for(LocalTime t:List.of(LocalTime.of(9,40),LocalTime.of(15,30)))service(t,true).tick();
        verify(mapper,times(2)).enqueue(any(),anyString(),anyString());
        clearInvocations(mapper,queue);
        for(LocalTime t:List.of(LocalTime.of(9,50),LocalTime.of(10,10),LocalTime.of(11,30),LocalTime.of(13,10),LocalTime.of(15,0),LocalTime.of(15,31)))service(t,true).tick();
        verifyNoInteractions(mapper,queue);
        assertTrue(PullbackDigestService.deliveryWindow(LocalTime.of(15,39,59)));assertFalse(PullbackDigestService.deliveryWindow(LocalTime.of(15,40)));
        assertFalse(service(LocalTime.of(10,10),true).deliverOne());
    }
    @Test void acknowledgedAndAmbiguousDeliveriesAreDistinct(){var row=new PullbackDigestMapper.Delivery();row.id=1L;row.claimToken="claim";row.payload="fixture";row.attempts=1;when(mapper.claim(eq(new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","test-group").destinationHash("test-group")),anyString())).thenReturn(row);when(client.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"OK"));assertTrue(service(LocalTime.of(9,40),true).deliverOne());verify(mapper).finish(1,"claim","SUCCESS",null);when(client.send(anyString(),anyString())).thenThrow(new RuntimeException("fixture"));service(LocalTime.of(15,30),true).deliverOne();verify(mapper).finish(1,"claim","UNKNOWN","DELIVERY_UNKNOWN");}
}
