package com.stocksentinel;

import com.stocksentinel.personal.*;
import com.stocksentinel.notification.*;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TObservationDeliveryTest {
    @Test void unknownDeliveryIsFinalAndKnownRetryIsBounded(){
        var mapper=mock(TObservationMapper.class);var observations=mock(TObservationService.class);when(observations.enabled()).thenReturn(true);
        var notifications=mock(NotificationMapper.class);var client=mock(NotificationClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<NotificationClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        var settings=new NotificationSettings(true,"SMART_BOT","test-bot","test-only-credential","test-group");
        var worker=new TObservationDelivery(mapper,observations,notifications,settings,provider,false);
        var row=new NotificationRow();row.id=1L;row.attempts=1;row.claimToken="test-token";row.targetChatId="test-group";row.payload="模拟做T提醒";
        when(mapper.claim(eq("test-group"),anyString())).thenReturn(row);
        when(client.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"NO_ACK"));
        assertTrue(worker.dispatchOne());verify(notifications).finish(1L,"test-token","UNKNOWN","DELIVERY_UNKNOWN",30);
        when(client.send(anyString(),anyString())).thenReturn(new NotificationClient.Delivery(NotificationClient.Outcome.RETRY,"BUSY"));
        assertTrue(worker.dispatchOne());verify(notifications).finish(1L,"test-token","RETRY","DELIVERY_RETRY",30);
        row.attempts=3;assertTrue(worker.dispatchOne());verify(notifications).finish(1L,"test-token","FAILED","DELIVERY_RETRY",30);
    }
}
