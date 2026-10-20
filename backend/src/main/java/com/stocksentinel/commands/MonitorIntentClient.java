package com.stocksentinel.commands;

public interface MonitorIntentClient {
    /** A null result means no supported add-monitor tool call. */
    String stockQuery(String text) throws Exception;
}
