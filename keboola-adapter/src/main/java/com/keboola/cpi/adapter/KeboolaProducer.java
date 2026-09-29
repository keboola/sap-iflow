package com.keboola.cpi.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes the message body as CSV into a Keboola Storage table. */
public class KeboolaProducer extends DefaultProducer {

    private static final Logger LOG = LoggerFactory.getLogger(KeboolaProducer.class);

    private static final Pattern IMPORT_SERVICE_ID = Pattern.compile("\"id\"\\s*:\\s*\"import\"");
    private static final Pattern SERVICE_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"error\"\\s*:\\s*\"((?:[^\"\\\\]++|\\\\.)*+)");

    private static final int ERROR_TEXT_LIMIT = 300;
    private static final int ERROR_BODY_READ = 8192;

    private final KeboolaEndpoint endpoint;

    public KeboolaProducer(final KeboolaEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public void process(final Exchange exchange) throws Exception {
        final String stackUrl = encrypted("Keboola Stack URL", required("Keboola Stack URL", endpoint.getStackUrl()));
        final String credentialName = required("Credential Name", endpoint.getCredentialName());
        final String tableId = required("Table", endpoint.getTableId());
        if (endpoint.getTimeoutMs() <= 0) {
            throw new KeboolaImportException(0, "Timeout (in ms) must be greater than 0 on the Keboola adapter");
        }

        final byte[] body = exchange.getIn().getBody(byte[].class);
        if (body == null || body.length == 0) {
            throw new KeboolaImportException(0, "Message body is empty; expected comma separated values to import into " + tableId);
        }

        final String token = endpoint.getTokenResolver().resolve(credentialName);
        final String importer = resolveImporterUrl(stackUrl);

        final Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("tableId", tableId);
        fields.put("incremental", endpoint.isIncremental() ? "1" : "0");

        final HttpResult result = postMultipart(importer + "/write-table", token, fields, body);

        exchange.getIn().setHeader("KeboolaTableId", tableId);
        exchange.getIn().setHeader("KeboolaIncremental", Boolean.valueOf(endpoint.isIncremental()));
        exchange.getIn().setHeader("KeboolaHttpStatus", Integer.valueOf(result.status));

        if (result.status < 200 || result.status >= 300) {
            throw new KeboolaImportException(result.status,
                    "Keboola import into '" + tableId + "' failed with status " + result.status + ": " + errorMessage(result));
        }
        exchange.getIn().setBody(result.body);
    }

    private static String required(final String label, final String value) throws KeboolaImportException {
        if (value == null || value.trim().isEmpty()) {
            throw new KeboolaImportException(0, label + " is not configured on the Keboola adapter");
        }
        return value.trim();
    }

    private static String encrypted(final String label, final String url) throws KeboolaImportException {
        final URL parsed;
        try {
            parsed = new URL(url);
        } catch (MalformedURLException e) {
            throw new KeboolaImportException(0, label + " is not a valid address: " + url, e);
        }
        final String protocol = parsed.getProtocol();
        final String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new KeboolaImportException(0, label + " is not a valid address: " + url);
        }
        final boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
        if ("https".equalsIgnoreCase(protocol) || ("http".equalsIgnoreCase(protocol) && local)) {
            return url;
        }
        throw new KeboolaImportException(0,
                label + " must start with https://, the Storage token is never sent unencrypted: " + url);
    }

    private String resolveImporterUrl(final String stackUrl) throws KeboolaImportException {
        final String configured = endpoint.getImporterUrl();
        if (configured != null && !configured.trim().isEmpty()) {
            return stripTrailingSlash(encrypted("Importer address", configured.trim()));
        }
        final String cached = endpoint.getResolvedImporterUrl();
        if (cached != null) {
            return cached;
        }
        final String stack = stripTrailingSlash(stackUrl);
        final HttpResult index;
        try {
            index = httpGet(stack + "/v2/storage?exclude=components");
        } catch (IOException e) {
            throw new KeboolaImportException(0,
                    "Could not reach the Keboola service list at " + stack + "/v2/storage: " + e.getMessage(), e);
        }
        if (index.status < 200 || index.status >= 300) {
            throw new KeboolaImportException(index.status,
                    "The Keboola service list at " + stack + "/v2/storage answered with status " + index.status + ": " + errorMessage(index));
        }
        final String discovered = findImporterInIndex(index.body);
        if (discovered == null) {
            throw new KeboolaImportException(0,
                    "The Keboola service list at " + stack + "/v2/storage names no importer; set Keboola Stack URL to your connection address");
        }
        final String importer = stripTrailingSlash(encrypted("Importer address from the Keboola service list", discovered));
        LOG.debug("Keboola importer for {} is {}", stack, importer);
        endpoint.setResolvedImporterUrl(importer);
        return importer;
    }

    private static String findImporterInIndex(final String json) {
        final Matcher id = IMPORT_SERVICE_ID.matcher(json);
        if (!id.find()) {
            return null;
        }
        final int from = Math.max(0, id.start() - 300);
        final int to = Math.min(json.length(), id.end() + 300);
        final Matcher url = SERVICE_URL.matcher(json.substring(from, to));
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        final int anchor = id.start() - from;
        while (url.find()) {
            final int distance = Math.abs(url.start() - anchor);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = url.group(1);
            }
        }
        return best == null ? null : best.replace("\\/", "/");
    }

    private static String errorMessage(final HttpResult result) {
        if (result.status >= 300 && result.status < 400) {
            return "the address redirects to " + (result.location == null ? "another address" : result.location)
                    + "; redirects are not followed";
        }
        final String body = result.body;
        if (body == null || body.isEmpty()) {
            return result.status == 401 ? "Keboola did not accept the Storage token" : "no response body";
        }
        final String head = body.length() > ERROR_BODY_READ ? body.substring(0, ERROR_BODY_READ) : body;
        final Matcher m = ERROR_MESSAGE.matcher(head);
        final String text = m.find() ? unescapeJson(m.group(1)) : head;
        return text.length() > ERROR_TEXT_LIMIT ? text.substring(0, ERROR_TEXT_LIMIT) : text;
    }

    private static String unescapeJson(final String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            final char next = text.charAt(++i);
            if (next == 'n' || next == 'r' || next == 't') {
                out.append(' ');
            } else if (next == 'u' && i + 4 < text.length()) {
                try {
                    out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                    i += 4;
                } catch (NumberFormatException e) {
                    out.append("\\u");
                }
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    private static String stripTrailingSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private HttpResult httpGet(final String url) throws IOException {
        final HttpURLConnection connection = open(url);
        connection.setRequestMethod("GET");
        return readResult(connection);
    }

    private HttpResult postMultipart(final String url, final String token, final Map<String, String> fields, final byte[] data)
            throws KeboolaImportException {
        final String boundary = "----keboola" + UUID.randomUUID();
        final StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\n");
            head.append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"\r\n\r\n");
            head.append(field.getValue()).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n");
        head.append("Content-Disposition: form-data; name=\"data\"; filename=\"data.csv\"\r\n");
        head.append("Content-Type: text/csv\r\n\r\n");
        final byte[] before = head.toString().getBytes(StandardCharsets.UTF_8);
        final byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        try {
            final HttpURLConnection connection = open(url);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("X-StorageApi-Token", token);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode((long) before.length + data.length + after.length);

            final OutputStream out = connection.getOutputStream();
            try {
                out.write(before);
                out.write(data);
                out.write(after);
            } finally {
                out.close();
            }
            final HttpResult result = readResult(connection);
            LOG.debug("Keboola importer answered {} for a delivery of {} bytes", Integer.valueOf(result.status), Integer.valueOf(data.length));
            return result;
        } catch (IOException e) {
            throw new KeboolaImportException(0, "Could not reach the Keboola importer at " + url + ": " + e.getMessage(), e);
        }
    }

    private HttpURLConnection open(final String url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(endpoint.getTimeoutMs());
        connection.setReadTimeout(endpoint.getTimeoutMs());
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static HttpResult readResult(final HttpURLConnection connection) throws IOException {
        final int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            try {
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } finally {
                stream.close();
            }
        }
        return new HttpResult(status, new String(buffer.toByteArray(), StandardCharsets.UTF_8), connection.getHeaderField("Location"));
    }

    static final class HttpResult {
        final int status;
        final String body;
        final String location;

        HttpResult(final int status, final String body, final String location) {
            this.status = status;
            this.body = body;
            this.location = location;
        }
    }
}
