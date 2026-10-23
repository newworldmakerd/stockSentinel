package com.stocksentinel;

import com.stocksentinel.commands.RemoveWatchIntent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoveWatchIntentTest {
    @Test void acceptsExplicitSingleStockCommands(){
        for(String text:new String[]{"取消关注四川美丰","请帮我删除关注 四川美丰","把四川美丰取消关注吧","停止监控四川美丰。","不再关注四川美丰"})
            assertTrue(RemoveWatchIntent.explicit(text,"四川美丰"),text);
        assertTrue(RemoveWatchIntent.explicit("取消关注６００４８７","600487"));
        assertTrue(RemoveWatchIntent.explicit("@股票助手 删除关注 600487","600487"));
    }
    @Test void rejectsQuestionsNegationQuotedInstructionsOtherUsersAndMultipleStocks(){
        for(String text:new String[]{"可以取消关注四川美丰吗？","不要删除关注四川美丰","别取消关注四川美丰","如何取消关注四川美丰","他说取消关注四川美丰","取消关注四川美丰和亨通光电","取消关注所有股票","删除关注张三的四川美丰","解释取消关注四川美丰","取消关注它"})
            assertFalse(RemoveWatchIntent.explicit(text,"四川美丰"),text);
    }
}
