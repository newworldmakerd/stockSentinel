package com.stocksentinel;

import com.stocksentinel.notification.*;
import com.stocksentinel.personal.*;
import com.stocksentinel.report.*;
import com.stocksentinel.signal.SignalMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiChatTest {
    NotificationSettings settings(){return new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","group-a","group-b,group-a");}
    @Test void configurationMergesDeduplicatesAndPreservesLegacyPrimary(){
        var s=settings();assertEquals(List.of("group-a","group-b"),s.chatIds);assertEquals("group-a",s.chatId);
        assertTrue(s.accepts("group-b"));assertFalse(s.accepts("other"));assertNotEquals(s.destinationHash("group-a"),s.destinationHash("group-b"));
        assertEquals("group-b",new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","","group-b").chatId);
        assertThrows(IllegalArgumentException.class,()->s.destinationHash("other"));
        assertThrows(IllegalArgumentException.class,()->new NotificationSettings(true,"SMART_BOT","b","test-only","","bad\nvalue"));
    }
    @Test void pollingAlternatesGroupsAndWorkersAreIndependent(){
        var s=settings();assertEquals("group-a",s.pollTargets("daily").getFirst());assertEquals("group-b",s.pollTargets("daily").getFirst());
        assertEquals("group-a",s.pollTargets("personal").getFirst());
    }
    @Test void signalIsEnqueuedOnceForEachDestinationAndPrimaryReturnIsCompatible(){
        var s=settings();var mapper=mock(NotificationMapper.class);var signals=mock(SignalMapper.class);
        when(signals.find(7)).thenReturn(new com.stocksentinel.common.entity.SignalEvent());
        var first=new NotificationRow();when(mapper.byKey("smart-bot:7:"+s.destinationHash("group-a"))).thenReturn(first);
        var service=new NotificationService(mapper,s,signals,mock(NotificationTemplate.class));
        assertSame(first,service.enqueue(7));
        verify(mapper).enqueue(7,"smart-bot:7:"+s.destinationHash("group-a"),"group-a");
        verify(mapper).enqueue(7,"smart-bot:7:"+s.destinationHash("group-b"),"group-b");
    }
    @SuppressWarnings("unchecked")
    @Test void personalDeliveryUsesClaimedGroupAndUnknownDoesNotBlockNextGroup(){
        var s=settings();var mapper=mock(PersonalPriceMapper.class);var client=mock(NotificationClient.class);
        var clients=(ObjectProvider<NotificationClient>)mock(ObjectProvider.class);when(clients.getIfAvailable()).thenReturn(client);
        var row=new PersonalPriceMapper.Event();row.id=1L;row.attempts=1;row.claimToken="claim";row.payload="personal";
        when(mapper.claim(eq(s.destinationHash("group-b")),anyString())).thenReturn(row);
        when(client.send("group-b","personal")).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"NO_ACK"));
        var service=new PersonalPriceDelivery(mapper,mock(DailyReportService.class),s,clients,true,false);
        assertTrue(service.dispatchOne());verify(client).send("group-b","personal");verify(client,never()).send(eq("group-a"),anyString());
        verify(mapper).finish(1,"claim","UNKNOWN","DELIVERY_UNKNOWN");
        when(mapper.claim(eq(s.destinationHash("group-b")),anyString())).thenReturn(null);
        var next=new PersonalPriceMapper.Event();next.id=2L;next.attempts=1;next.claimToken="next";next.payload="other";
        when(mapper.claim(eq(s.destinationHash("group-a")),anyString())).thenReturn(next);
        when(client.send("group-a","other")).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.SUCCESS,"OK"));
        assertTrue(service.dispatchOne());verify(mapper).finish(2,"next","SUCCESS",null);
    }
    @Test void oneWebSocketConnectionCanSendToBothGroups() throws Exception {
        try(var bot=new SmartBotProtocolTest.Bot("SUCCESS")){
            var client=bot.client();try{
                assertEquals(NotificationClient.Outcome.SUCCESS,client.send("group-a","first").outcome());
                assertEquals(NotificationClient.Outcome.SUCCESS,client.send("group-b","second").outcome());
                assertEquals(List.of("group-a","group-b"),bot.targets);assertEquals(1,bot.auths.get());
            }finally{client.destroy();}
        }
    }
    @SuppressWarnings("unchecked")
    @Test void dailyReportRendersOnceAndEnqueuesBothGroups(){
        var s=settings();var mapper=mock(ReportMapper.class);var dates=mock(com.stocksentinel.jobs.TradingDatePolicy.class);
        var date=java.time.LocalDate.of(2026,10,21);when(dates.today()).thenReturn(date);when(dates.afterClose()).thenReturn(true);when(dates.calendarStatus(date)).thenReturn("OPEN");
        var service=spy(new DailyReportService(mapper,mock(com.stocksentinel.stock.WatchlistMapper.class),s,mock(ObjectProvider.class),dates,new com.fasterxml.jackson.databind.ObjectMapper(),true));
        doReturn("report snapshot").when(service).render(date);service.enqueue(date);
        verify(service,times(1)).render(date);
        verify(mapper).enqueue(date,s.destinationHash("group-a"),"report snapshot");
        verify(mapper).enqueue(date,s.destinationHash("group-b"),"report snapshot");
    }
}
