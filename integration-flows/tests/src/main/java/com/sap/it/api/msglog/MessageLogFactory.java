package com.sap.it.api.msglog;

import java.util.IdentityHashMap;
import java.util.Map;

import com.sap.gateway.ip.core.customdev.util.Message;

/** Test stand-in for the messageLogFactory binding: one log per message, kept for assertions. */
public class MessageLogFactory {

    private final Map<Message, MessageLog> logs = new IdentityHashMap<Message, MessageLog>();
    private boolean available = true;

    public MessageLog getMessageLog(final Message message) {
        return available ? logOf(message) : null;
    }

    /** The log of a message, empty when the script never asked for it. */
    public MessageLog logOf(final Message message) {
        MessageLog log = logs.get(message);
        if (log == null) {
            log = new MessageLog();
            logs.put(message, log);
        }
        return log;
    }

    /** With false, getMessageLog answers null as the runtime does outside a message context. */
    public void setAvailable(final boolean available) {
        this.available = available;
    }
}
