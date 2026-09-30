package com.sap.gateway.ip.core.customdev.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test stand-in for the Cloud Integration message: body, headers, properties, attachments. */
public class Message {

    private Object body;
    private Map<String, Object> headers = new LinkedHashMap<String, Object>();
    private Map<String, Object> properties = new LinkedHashMap<String, Object>();
    private Map<String, Object> attachments = new LinkedHashMap<String, Object>();

    public Object getBody() {
        return body;
    }

    /** Converts between String, byte[], Reader and InputStream like the runtime; anything else is an error. */
    @SuppressWarnings("unchecked")
    public <T> T getBody(final Class<T> type) {
        if (body == null) {
            return null;
        }
        if (type.isInstance(body)) {
            return (T) body;
        }
        try {
            if (body instanceof String) {
                return (T) fromText((String) body, type);
            }
            if (body instanceof byte[]) {
                return (T) fromBytes((byte[]) body, type);
            }
            if (body instanceof Reader) {
                return (T) fromText(readAll((Reader) body), type);
            }
            if (body instanceof InputStream) {
                return (T) fromBytes(readAll((InputStream) body), type);
            }
            if (type == String.class) {
                return (T) body.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException("no conversion from " + body.getClass().getName() + " to " + type.getName());
    }

    public void setBody(final Object body) {
        this.body = body;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(final Map<String, Object> headers) {
        this.headers = headers;
    }

    /** A null value is kept as a header with no value, as the runtime does. */
    public void setHeader(final String name, final Object value) {
        headers.put(name, value);
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(final Map<String, Object> properties) {
        this.properties = properties;
    }

    public void setProperty(final String name, final Object value) {
        properties.put(name, value);
    }

    public Object getProperty(final String name) {
        return properties.get(name);
    }

    public Map<String, Object> getAttachments() {
        return attachments;
    }

    public void setAttachments(final Map<String, Object> attachments) {
        this.attachments = attachments;
    }

    private static Object fromText(final String text, final Class<?> type) {
        if (type == String.class) {
            return text;
        }
        if (type == Reader.class) {
            return new StringReader(text);
        }
        if (type == byte[].class) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (type == InputStream.class) {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("no conversion from String to " + type.getName());
    }

    private static Object fromBytes(final byte[] bytes, final Class<?> type) {
        if (type == byte[].class) {
            return bytes;
        }
        if (type == String.class) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (type == InputStream.class) {
            return new ByteArrayInputStream(bytes);
        }
        if (type == Reader.class) {
            return new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("no conversion from byte[] to " + type.getName());
    }

    private static String readAll(final Reader reader) throws IOException {
        final StringWriter out = new StringWriter();
        final char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) > 0) {
            out.write(buffer, 0, count);
        }
        return out.toString();
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) > 0) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
