package com.stocksentinel.notification;

public interface NotificationClient {
    enum Outcome { SUCCESS, RETRY, REJECTED, UNKNOWN }
    /** Outcomes never carry raw provider frames, credentials or exception messages. */
    record Delivery(Outcome outcome, String code) {}
    Delivery send(String chatId, String markdown);
}
