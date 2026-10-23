package com.stocksentinel;

import com.stocksentinel.commands.*;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupConversationTest {
    static class TestClock extends Clock {Instant now=Instant.parse("2026-10-21T02:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
    @Test void memoryIsPerUserAndExpiresWithoutPersistingTranscripts()throws Exception {
        var tools=mock(GroupAssistantTools.class);when(tools.group(any())).thenReturn("group");var client=mock(GroupAssistantClient.class);var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(client);
        var last=new AtomicReference<List<Map<String,Object>>>();when(client.reply(anyList(),any())).thenAnswer(call->{last.set(List.copyOf(call.getArgument(0)));return "固定答复";});var clock=new TestClock();var service=new GroupConversationService(true,provider,tools,clock);
        service.reply(new GroupMessage("1","b","g","u1","第一个问题",null));assertEquals(1,last.get().size());
        service.reply(new GroupMessage("2","b","g","u1","接着问",null));assertEquals(3,last.get().size());
        service.reply(new GroupMessage("3","b","g","u2","另一个人",null));assertEquals(1,last.get().size());
        clock.now=clock.now.plusSeconds(1201);service.reply(new GroupMessage("4","b","g","u1","过期后问",null));assertEquals(1,last.get().size());
    }
    @Test void disabledConversationMakesNoModelCall(){var provider=mock(ObjectProvider.class);var service=new GroupConversationService(false,provider,mock(GroupAssistantTools.class),Clock.systemUTC());assertThrows(Exception.class,()->service.reply(new GroupMessage("1","b","g","u","hi",null)));verifyNoInteractions(provider);}
}
