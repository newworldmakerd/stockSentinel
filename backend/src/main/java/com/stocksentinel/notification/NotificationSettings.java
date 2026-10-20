package com.stocksentinel.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** No toString: credentials must never appear in logs or API responses. */
@Component
public class NotificationSettings {
    public final boolean enabled;
    public final String mode, botId, secret, chatId;
    public NotificationSettings(@Value("${wecom.enabled:false}") boolean enabled,
            @Value("${wecom.mode:SMART_BOT}") String mode,
            @Value("${wecom.bot-id:}") String botId, @Value("${wecom.bot-secret:}") String secret,
            @Value("${wecom.chat-id:}") String chatId) {
        this.enabled=enabled; this.mode=mode; this.botId=botId; this.secret=secret; this.chatId=chatId;
        if (enabled && (!"SMART_BOT".equals(mode) || botId.isBlank() || secret.isBlank()
                || chatId.isBlank() || chatId.length()>256))
            throw new IllegalArgumentException("Enabled smart bot requires credentials and target chat configuration");
    }
}
