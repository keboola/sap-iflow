package com.sap.it.api.msglog;

import java.util.Date;
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

    public void setIntegerProperty(final String name, final Integer value) {
        properties.put(name, value);
    }

    public void setLongProperty(final String name, final Long value) {
        properties.put(name, value);
    }

    public void setFloatProperty(final String name, final Float value) {
        properties.put(name, value);
    }

    public void setDoubleProperty(final String name, final Double value) {
        properties.put(name, value);
    }

    public void setBooleanProperty(final String name, final Boolean value) {
        properties.put(name, value);
    }

    public void setDateProperty(final String name, final Date value) {
        properties.put(name, value);
    }

    public void addAttachmentAsString(final String name, final String content, final String mediaType) {
        attachments.put(name, new Attachment(content, mediaType));
    }

    /** Searchable properties written with addCustomHeaderProperty, last value per name. */
    public Map<String, String> getCustomHeaderProperties() {
        return customHeaderProperties;
    }

    /** Values written with set*Property. */
    public Map<String, Object> getProperties() {
        return properties;
    }

    public Map<String, Attachment> getAttachments() {
        return attachments;
    }
}
