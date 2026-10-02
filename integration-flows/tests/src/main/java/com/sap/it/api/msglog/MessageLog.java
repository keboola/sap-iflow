package com.sap.it.api.msglog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test stand-in for the message processing log: keeps what a script writes to the monitor. */
public class MessageLog {

    /** One attachment: its text and media type. */
    public static class Attachment {

        private final String content;
        private final String mediaType;

        Attachment(final String content, final String mediaType) {
            this.content = content;
            this.mediaType = mediaType;
        }

        public String getContent() {
            return content;
        }

        public String getMediaType() {
            return mediaType;
        }
    }

    private final Map<String, String> customHeaderProperties = new LinkedHashMap<String, String>();
    private final Map<String, Object> properties = new LinkedHashMap<String, Object>();
    private final Map<String, Attachment> attachments = new LinkedHashMap<String, Attachment>();

    public void addCustomHeaderProperty(final String name, final String value) {
        customHeaderProperties.put(name, value);
    }

    public void setStringProperty(final String name, final String value) {
        properties.put(name, value);
    }

    public void addAttachmentAsString(final String name, final String content, final String mediaType) {
        attachments.put(name, new Attachment(content, mediaType));
    }

    /** Searchable properties written with addCustomHeaderProperty, last value per name. */
    public Map<String, String> getCustomHeaderProperties() {
        return customHeaderProperties;
    }

    /** Values written with setStringProperty. */
    public Map<String, Object> getProperties() {
        return properties;
    }

    public Map<String, Attachment> getAttachments() {
        return attachments;
    }
}
