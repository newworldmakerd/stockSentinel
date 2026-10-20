package com.stocksentinel.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Standalone receive-only CLI; bypasses Spring, database, model and notification worker. */
public final class ChatIdDiscovery {
    private ChatIdDiscovery() {}
    public static int run(Map<String,String> environment,PrintStream output) {
        String bot=environment.getOrDefault("WECOM_BOT_ID","");
        String secret=environment.getOrDefault("WECOM_BOT_SECRET","");
        if(bot.isBlank() || secret.isBlank()) {
            output.println("需要通过环境变量配置 WECOM_BOT_ID 和 WECOM_BOT_SECRET；无需配置 chatid。");
            return 2;
        }
        var found=new CompletableFuture<String>();
        SmartBotClient client=null;
        Thread cleanup=null;
        try {
            output.println("chatid 发现模式：只接收，不发消息；不启动数据库、模型或通知任务。");
            output.println("请在目标群 @机器人 发一条消息；最多等待180秒，获取第一个群chatid后退出。");
            client=SmartBotClient.discovery(new ObjectMapper(),bot,secret,found::complete);
            var active=client;
            cleanup=new Thread(active::destroy,"wecom-discovery-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanup);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            boolean announced=false;
            while(System.nanoTime()<deadline) {
                String status=client.discoveryStatus();
                if(status.equals("AUTHENTICATED") && !announced) {
                    output.println("鉴权成功，等待目标群消息……");announced=true;
                }
                if(status.equals("AUTH_REJECTED") || status.equals("TAKEN_OVER") || status.equals("CONNECTION_FAILED")) {
                    output.println("发现模式终止："+status);return 3;
                }
                try {
                    String id=found.get(1,TimeUnit.SECONDS);
                    output.println("已发现群聊 chatid："+id);
                    output.println("请将该值配置到 WECOM_CHAT_ID。未发送任何消息。");return 0;
                } catch(TimeoutException waiting) { /* bounded wait, no callback content logs */ }
            }
            output.println("等待超时，未收到有效群消息；未发送任何消息。");return 4;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();output.println("发现模式已中断。");return 130;
        } catch(Exception failure) {
            output.println("发现模式失败；未记录凭据、原始消息或异常详情。");return 1;
        } finally {
            if(client!=null) client.destroy();
            if(cleanup!=null) try {Runtime.getRuntime().removeShutdownHook(cleanup);} catch(IllegalStateException shuttingDown) { }
        }
    }
}
