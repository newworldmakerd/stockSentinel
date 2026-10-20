package com.stocksentinel.notification;

import com.stocksentinel.common.entity.NotifyRecord;
import java.time.OffsetDateTime;

public class NotificationRow extends NotifyRecord {
    public String targetChatId, payload, claimToken;
    public OffsetDateTime leaseUntil;
}
