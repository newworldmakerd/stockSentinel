package com.stocksentinel.commands;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

/** Short-lived, per-user memory only. No transcripts are persisted or logged. */
@Service
public class GroupConversationService {
    private final boolean enabled;private final ObjectProvider<GroupAssistantClient> clients;private final GroupAssistantTools tools;private final Clock clock;
    private record Memory(Instant touched,List<Map<String,Object>> turns) {}
    private final LinkedHashMap<String,Memory> memories=new LinkedHashMap<>();
    public GroupConversationService(@Value("${wecom.commands.chat-enabled:false}") boolean enabled,ObjectProvider<GroupAssistantClient> clients,GroupAssistantTools tools,Clock clock){this.enabled=enabled;this.clients=clients;this.tools=tools;this.clock=clock;}
    public boolean enabled(){return enabled;}
    public synchronized String reply(GroupMessage message) {
        if(!enabled)throw new IllegalStateException("Chat disabled");
        String key=tools.group(message)+":"+message.userId;var now=clock.instant();
        memories.entrySet().removeIf(e->e.getValue().touched.isBefore(now.minusSeconds(1200)));
        var previous=memories.get(key);var turns=new ArrayList<Map<String,Object>>(previous==null?List.of():previous.turns);
        turns.add(Map.of("role","user","content",message.text));var writeUsed=new AtomicBoolean();
        try {
            String answer=clients.getObject().reply(turns,(name,args)->tools.execute(message,name,args,writeUsed));
            if(answer==null||answer.isBlank())throw new IllegalStateException("Empty reply");
            turns.add(Map.of("role","assistant","content",answer));
            while(turns.size()>12)turns.removeFirst();
            memories.remove(key);while(memories.size()>=100)memories.remove(memories.keySet().iterator().next());
            memories.put(key,new Memory(now,List.copyOf(turns)));return answer;
        }catch(Exception unavailable){
            return writeUsed.get()?"本次对话未完整完成；关注增删、昵称、仓位、做T成交或个人价位操作可能已经执行，请查询对应列表核实，勿根据本条判断失败。":"本次对话暂不可用，未执行写操作，请稍后重试。";
        }
    }
}
