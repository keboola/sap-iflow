package com.keboola.cpi.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class KeboolaAdapterTest extends CamelTestSupport {

    private static HttpServer server;
    private static int port;

    private static HttpServer elsewhere;
    private static int elsewherePort;
    private static final AtomicInteger elsewhereCalls = new AtomicInteger();

    private static volatile int importerStatus;
    private static volatile String importerResponse;
    private static volatile String importerRedirect;
    private static volatile int storageIndexStatus;
    private static volatile String storageIndexResponse;
    private static final AtomicInteger importerCalls = new AtomicInteger();
    private static final Map<String, String> captured = new ConcurrentHashMap<String, String>();

    private static final String CSV = "id,name\n1,alpha\n2,beta\n";

    @BeforeClass
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/write-table", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                importerCalls.incrementAndGet();
                captured.put("token", String.valueOf(exchange.getRequestHeaders().getFirst("X-StorageApi-Token")));
                captured.put("contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
                captured.put("body", readAll(exchange.getRequestBody()));
                if (importerRedirect != null) {
                    exchange.getResponseHeaders().set("Location", importerRedirect);
                    exchange.sendResponseHeaders(importerStatus, -1);
                    exchange.close();
                    return;
                }
                respond(exchange, importerStatus, importerResponse);
            }
        });
        server.createContext("/v2/storage", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                captured.put("indexToken", String.valueOf(exchange.getRequestHeaders().getFirst("X-StorageApi-Token")));
                captured.put("indexQuery", String.valueOf(exchange.getRequestURI().getRawQuery()));
                respond(exchange, storageIndexStatus, storageIndexResponse);
            }
        });
        server.start();

        elsewhere = HttpServer.create(new InetSocketAddress(0), 0);
        elsewherePort = elsewhere.getAddress().getPort();
        elsewhere.createContext("/", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                elsewhereCalls.incrementAndGet();
                readAll(exchange.getRequestBody());
                respond(exchange, 200, "{\"from\":\"elsewhere\"}");
            }
        });
        elsewhere.start();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (elsewhere != null) {
            elsewhere.stop(0);
        }
    }

    @Before
    public void resetServerState() {
        importerStatus = 200;
        importerResponse = "{\"id\":123,\"status\":\"ok\"}";
        importerRedirect = null;
        storageIndexStatus = 200;
        storageIndexResponse = "{\"services\":[{\"id\":\"docker-runner\",\"url\":\"http:\\/\\/other:1\"},"
                + "{\"id\":\"import\",\"url\":\"http:\\/\\/localhost:" + port + "\"}]}";
        importerCalls.set(0);
        elsewhereCalls.set(0);
        captured.clear();
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        final OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static String readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private String uri(final String extra) {
        return "keboola:target?stackUrl=http://localhost:" + port
                + "&credentialName=TEST&tableId=in.c-test.rows" + extra;
    }

    private KeboolaEndpoint prepared(final String uri) {
        final KeboolaEndpoint endpoint = context.getEndpoint(uri, KeboolaEndpoint.class);
        endpoint.setTokenResolver(new TokenResolver() {
            @Override
            public String resolve(final String credentialName) {
                return "test-token-for-" + credentialName;
            }
        });
        return endpoint;
    }

    private KeboolaImportException failure(final String uri) {
        prepared(uri);
        try {
            template.requestBody(uri, CSV, String.class);
        } catch (CamelExecutionException e) {
            assertTrue("expected the adapter's own exception but got " + e.getCause(), e.getCause() instanceof KeboolaImportException);
            assertFalse("the token must not appear in an error text", e.getCause().getMessage().contains("test-token-for-"));
            return (KeboolaImportException) e.getCause();
        }
        fail("expected the delivery to fail");
        return null;
    }

    @Test
    public void deliversCsvAsMultipartWithTokenHeader() {
        final String uri = uri("&importerUrl=http://localhost:" + port);
        prepared(uri);

        final String response = template.requestBody(uri, CSV, String.class);

        assertEquals("{\"id\":123,\"status\":\"ok\"}", response);
        assertEquals("test-token-for-TEST", captured.get("token"));
        assertTrue(captured.get("contentType").startsWith("multipart/form-data; boundary="));
        final String body = captured.get("body");
        assertTrue(body.contains("name=\"tableId\"\r\n\r\nin.c-test.rows"));
        assertTrue(body.contains("name=\"incremental\"\r\n\r\n1"));
        assertTrue(body.contains("filename=\"data.csv\""));
        assertTrue(body.contains("Content-Type: text/csv"));
        assertTrue(body.contains(CSV));
    }

    @Test
    public void fullLoadSendsIncrementalZero() {
        final String uri = uri("&incremental=false&importerUrl=http://localhost:" + port);
        prepared(uri);

        template.requestBody(uri, CSV, String.class);

        assertTrue(captured.get("body").contains("name=\"incremental\"\r\n\r\n0"));
    }

    @Test
    public void discoversImporterFromStorageServiceIndex() {
        final String uri = uri("");
        prepared(uri);

        final String response = template.requestBody(uri, CSV, String.class);

        assertEquals("{\"id\":123,\"status\":\"ok\"}", response);
        assertTrue(captured.get("body").contains(CSV));
        assertEquals("exclude=components", captured.get("indexQuery"));
    }

    @Test
    public void serviceListIsReadWithoutTheToken() {
        final String uri = uri("");
        prepared(uri);

        template.requestBody(uri, CSV, String.class);

        assertEquals("null", captured.get("indexToken"));
        assertEquals("test-token-for-TEST", captured.get("token"));
    }

    @Test
    public void keboolaErrorTextSurvivesIntoException() {
        importerStatus = 404;
        importerResponse = "{\"error\":\"Table in.c-test.rows not found\",\"code\":404}";
        final String uri = uri("&importerUrl=http://localhost:" + port);
        prepared(uri);

        try {
            template.requestBody(uri, CSV, String.class);
            fail("expected the import to fail");
        } catch (CamelExecutionException e) {
            final KeboolaImportException cause = (KeboolaImportException) e.getCause();
            assertEquals(404, cause.getHttpStatus());
            assertTrue(cause.getMessage().contains("Table in.c-test.rows not found"));
        }
    }

    @Test
    public void errorTextWithQuotationMarksIsKeptWhole() {
        importerStatus = 400;
        importerResponse = "{\"error\":\"Column \\\"id\\\" is missing in https:\\/\\/example.test\\/file\",\"code\":400}";

        final KeboolaImportException cause = failure(uri("&importerUrl=http://localhost:" + port));

        assertEquals(400, cause.getHttpStatus());
        assertTrue(cause.getMessage(), cause.getMessage().endsWith("Column \"id\" is missing in https://example.test/file"));
    }

    @Test
    public void errorPageThatIsNotJsonIsShortened() {
        importerStatus = 502;
        final StringBuilder page = new StringBuilder("<html><body>502 Bad Gateway");
        for (int i = 0; i < 2000; i++) {
            page.append(" filler");
        }
        importerResponse = page.toString();

        final KeboolaImportException cause = failure(uri("&importerUrl=http://localhost:" + port));

        assertEquals(502, cause.getHttpStatus());
        assertTrue(cause.getMessage().contains("502 Bad Gateway"));
        assertTrue(cause.getMessage().length() < 500);
    }

    @Test
    public void refusedTokenIsNamed() {
        importerStatus = 401;
        importerResponse = "{\"error\":\"Invalid access token\",\"code\":401}";

        final KeboolaImportException cause = failure(uri("&importerUrl=http://localhost:" + port));

        assertEquals(401, cause.getHttpStatus());
        assertTrue(cause.getMessage(), cause.getMessage().toLowerCase().contains("token"));
    }

    @Test
    public void redirectThatKeepsTheMethodIsNotFollowed() {
        importerStatus = 307;
        importerRedirect = "http://127.0.0.1:" + elsewherePort + "/write-table";

        final KeboolaImportException cause = failure(uri("&importerUrl=http://localhost:" + port));

        assertEquals(307, cause.getHttpStatus());
        assertTrue(cause.getMessage(), cause.getMessage().contains("redirects are not followed"));
        assertEquals("neither the rows nor the token may reach the other address", 0, elsewhereCalls.get());
    }

    @Test
    public void redirectIsNotTakenForADelivery() {
        importerStatus = 302;
        importerRedirect = "http://127.0.0.1:" + elsewherePort + "/login";

        final KeboolaImportException cause = failure(uri("&importerUrl=http://localhost:" + port));

        assertEquals(302, cause.getHttpStatus());
        assertEquals(0, elsewhereCalls.get());
    }

    @Test
    public void stackAddressWithoutEncryptionIsRefused() {
        final KeboolaImportException cause = failure(
                "keboola:target?stackUrl=http://connection.keboola.invalid&credentialName=TEST&tableId=in.c-test.rows");

        assertTrue(cause.getMessage(), cause.getMessage().contains("Keboola Stack URL must start with https://"));
        assertEquals(0, importerCalls.get());
    }

    @Test
    public void importerAddressWithoutEncryptionIsRefused() {
        final KeboolaImportException cause = failure(uri("&importerUrl=http://import.keboola.invalid"));

        assertTrue(cause.getMessage(), cause.getMessage().contains("must start with https://"));
        assertEquals(0, importerCalls.get());
    }

    @Test
    public void importerFromTheServiceListWithoutEncryptionIsRefused() {
        storageIndexResponse = "{\"services\":[{\"id\":\"import\",\"url\":\"http:\\/\\/import.keboola.invalid\"}]}";

        final KeboolaImportException cause = failure(uri(""));

        assertTrue(cause.getMessage(), cause.getMessage().contains("must start with https://"));
        assertEquals(0, importerCalls.get());
    }

    @Test
    public void addressWithoutHostIsRefused() {
        final KeboolaImportException cause = failure(
                "keboola:target?stackUrl=https:/connection.keboola.invalid&credentialName=TEST&tableId=in.c-test.rows");

        assertTrue(cause.getMessage(), cause.getMessage().contains("Keboola Stack URL is not a valid address"));
        assertEquals(0, importerCalls.get());
    }

    @Test
    public void addressThatOnlyLooksLocalIsRefused() {
        final KeboolaImportException cause = failure(
                "keboola:target?stackUrl=http://localhost.keboola.invalid&credentialName=TEST&tableId=in.c-test.rows");

        assertTrue(cause.getMessage(), cause.getMessage().contains("must start with https://"));
    }

    @Test
    public void importerIsNotGuessedWhenTheServiceListCannotBeReached() throws IOException {
        final int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        final KeboolaImportException cause = failure(
                "keboola:target?stackUrl=https://localhost:" + closedPort + "&credentialName=TEST&tableId=in.c-test.rows&timeoutMs=2000");

        assertEquals(0, cause.getHttpStatus());
        assertTrue(cause.getMessage(), cause.getMessage().contains("Could not reach the Keboola service list"));
        assertFalse(cause.getMessage(), cause.getMessage().contains("import."));
    }

    @Test
    public void importerIsNotGuessedWhenTheServiceListRefuses() {
        storageIndexStatus = 500;
        storageIndexResponse = "{\"error\":\"Application error\"}";

        final KeboolaImportException cause = failure(uri(""));

        assertEquals(500, cause.getHttpStatus());
        assertTrue(cause.getMessage(), cause.getMessage().contains("answered with status 500: Application error"));
        assertEquals(0, importerCalls.get());
    }

    @Test
    public void serviceListWithoutAnImporterIsAConfigurationError() {
        storageIndexResponse = "{\"services\":[{\"id\":\"queue\",\"url\":\"https:\\/\\/queue.keboola.invalid\"}]}";

        final KeboolaImportException cause = failure(uri(""));

        assertTrue(cause.getMessage(), cause.getMessage().contains("names no importer"));
        assertEquals(0, importerCalls.get());
    }

    @Test
    public void lookupThatFailedIsAskedAgain() {
        final String uri = uri("");
        storageIndexStatus = 503;
        storageIndexResponse = "{\"error\":\"Maintenance\"}";
        assertEquals(503, failure(uri).getHttpStatus());

        storageIndexStatus = 200;
        storageIndexResponse = "{\"services\":[{\"id\":\"import\",\"url\":\"http:\\/\\/localhost:" + port + "\"}]}";

        assertEquals("{\"id\":123,\"status\":\"ok\"}", template.requestBody(uri, CSV, String.class));
    }

    @Test
    public void timeoutMustBeGreaterThanZero() {
        assertTrue(failure(uri("&timeoutMs=0&importerUrl=http://localhost:" + port)).getMessage().contains("greater than 0"));
        assertTrue(failure(uri("&timeoutMs=-5&importerUrl=http://localhost:" + port)).getMessage().contains("greater than 0"));
        assertEquals(0, importerCalls.get());
    }

    @Test
    public void emptyBodyFailsFast() {
        final String uri = uri("&importerUrl=http://localhost:" + port);
        prepared(uri);

        try {
            template.requestBody(uri, "", String.class);
            fail("expected the empty delivery to fail");
        } catch (CamelExecutionException e) {
            final KeboolaImportException cause = (KeboolaImportException) e.getCause();
            assertEquals(0, cause.getHttpStatus());
            assertTrue(cause.getMessage().contains("empty"));
        }
    }

    @Test
    public void missingTableConfigurationFailsFast() {
        final String uri = "keboola:target?stackUrl=http://localhost:" + port + "&credentialName=TEST";
        prepared(uri);

        try {
            template.requestBody(uri, CSV, String.class);
            fail("expected the unconfigured endpoint to fail");
        } catch (CamelExecutionException e) {
            final KeboolaImportException cause = (KeboolaImportException) e.getCause();
            assertTrue(cause.getMessage().contains("Table is not configured"));
        }
    }
}
