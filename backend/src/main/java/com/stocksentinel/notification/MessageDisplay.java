package com.stocksentinel.notification;

import com.stocksentinel.jobs.TradingDatePolicy;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** Presentation only; does not change stored instants or rule inputs. */
public final class MessageDisplay {
    private MessageDisplay() {}
    public static String time(OffsetDateTime value) {
        return value==null?"未记录":value.atZoneSameInstant(TradingDatePolicy.ZONE)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
