import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

static String cfg(Message message, String key, String fallback) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return fallback }
    return v
}

static String stripSlash(String url) {
    return url?.endsWith("/") ? url.substring(0, url.length() - 1) : url
}

static boolean isFinal(int status) {
    return status >= 300 && status < 500 && status != 408 && status != 429
}

static boolean isHttps(String url) {
    return url != null && url.toLowerCase().startsWith("https://")
}

static Map httpCall(String url, String token, String method, String contentType, byte[] body,
                    int timeoutMs) {
    HttpURLConnection connection = null
    try {
        connection = (HttpURLConnection) new URL(url).openConnection()
        connection.setInstanceFollowRedirects(false)
        connection.setRequestMethod(method)
        connection.setRequestProperty("X-StorageApi-Token", token)
        connection.setConnectTimeout(timeoutMs)
        connection.setReadTimeout(timeoutMs)
        if (contentType) { connection.setRequestProperty("Content-Type", contentType) }
        if (body != null) {
            connection.setDoOutput(true)
            def out = connection.getOutputStream()
            try { out.write(body) } finally { out.close() }
        }
        int status = connection.getResponseCode()
        def stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()
        def text = stream != null ? stream.getText("UTF-8") : ""
        def location = (status >= 300 && status < 400) ? (connection.getHeaderField("Location") ?: "") : ""
        return [status: status, body: text, location: location]
    } catch (Exception e) {
        def detail = e.getMessage() ?: ""
        if (token) { detail = detail.replace(token, "***") }
        return [status: 0, body: (e.getClass().getSimpleName() + ": " + detail), location: ""]
    } finally {
        if (connection != null) { try { connection.disconnect() } catch (Exception ignored) { } }
    }
}

static String withRedirect(String sentence, Map answer) {
    int status = (int) (answer.status ?: 0)
    if (status < 300 || status >= 400) { return sentence }
    def location = (answer.location ?: "").toString().take(200)
    return sentence.trim() + " Keboola answered with a redirect" + (location ? " to " + location : "") +
           ", which this integration flow does not follow."
}

static String findImportUrl(Object node) {
    if (node instanceof Map) {
        if (node["id"]?.toString() == "import" && node["url"]) { return node["url"].toString() }
        for (value in node.values()) {
            def found = findImportUrl(value)
            if (found) { return found }
        }
    } else if (node instanceof List) {
        for (value in node) {
            def found = findImportUrl(value)
            if (found) { return found }
        }
    }
    return null
}

static Map discoverImporter(String stackUrl, String token, int timeoutMs, List notes) {
    def index = httpCall(stackUrl + "/v2/storage?exclude=components", token, "GET", null, null, timeoutMs)
    if (index.status == 200) {
        try {
            def found = findImportUrl(new JsonSlurper().parse(new StringReader(index.body)))
            if (found) { return [url: stripSlash(found), status: 200, body: "", location: ""] }
        } catch (Exception e) {
            notes << ("service index could not be parsed: " + e.getMessage())
        }
    }
    return [url: null, status: index.status, body: (index.body ?: ""), location: (index.location ?: "")]
}

static byte[] multipart(String boundary, Map fields, byte[] data) {
    def out = new ByteArrayOutputStream()
    def write = { String s -> out.write(s.getBytes("UTF-8")) }
    fields.each { key, value ->
        write("--" + boundary + "\r\n")
        write("Content-Disposition: form-data; name=\"" + key + "\"\r\n\r\n")
        write(value.toString() + "\r\n")
    }
    write("--" + boundary + "\r\n")
    write("Content-Disposition: form-data; name=\"data\"; filename=\"data.csv\"\r\n")
    write("Content-Type: text/csv\r\n\r\n")
    out.write(data)
    write("\r\n--" + boundary + "--\r\n")
    return out.toByteArray()
}

static List primaryKeyOf(String tableDetail) {
    try {
        def detail = new JsonSlurper().parse(new StringReader(tableDetail ?: ""))
        def key = (detail instanceof Map) ? detail["primaryKey"] : null
        return (key instanceof List) ? key.collect { it?.toString() } : null
    } catch (Exception ignored) {
        return null
    }
}

static Map ensureTable(String stackUrl, String token, String tableId, String header,
                       String primaryKey, int timeoutMs) {
    def keys = primaryKey ? primaryKey.split(",").collect { it.trim() }.findAll { it } : []
    def probe = httpCall(stackUrl + "/v2/storage/tables/" + tableId, token, "GET", null, null, timeoutMs)
    if (probe.status == 200) {
        def theirs = keys ? primaryKeyOf(probe.body) : null
        if (theirs != null && (theirs as Set) != (keys as Set)) {
            return [ok: false, final: true,
                    text: "exists with primary key [" + theirs.join(", ") + "] in Keboola, but PRIMARY_KEY is [" +
                          keys.join(", ") + "]. Set the same key in both places: without it, records " +
                          "delivered again are added instead of updated."]
        }
        return [ok: true, text: "exists"]
    }
    if (probe.status != 404) {
        return [ok: false, final: isFinal((int) probe.status), status: probe.status, body: probe.body,
                text: withRedirect("could not be checked (HTTP " + probe.status + "): " + probe.body.take(200), probe)]
    }
    // Table creation
    int cut = tableId.lastIndexOf(".")
    if (cut <= 0) { return [ok: false, final: true, text: "table id '" + tableId + "' is not <bucket>.<table>"] }
    def bucket = tableId.substring(0, cut)
    def name = tableId.substring(cut + 1)
    def columns = header.split(",").collect { it.trim().replaceAll(/^"|"$/, "") }.findAll { it }
    def payload = JsonOutput.toJson([
        name: name,
        primaryKeysNames: keys,
        columns: columns.collect { [name: it, basetype: "STRING"] },
    ])
    def created = httpCall(stackUrl + "/v2/storage/buckets/" + bucket + "/tables-definition",
                           token, "POST", "application/json", payload.getBytes("UTF-8"), timeoutMs)
    if (created.status != 202 && created.status != 200 && created.status != 201) {
        return [ok: false, final: isFinal((int) created.status), status: created.status, body: created.body,
                text: withRedirect("creation failed (HTTP " + created.status + "): " + created.body.take(300), created)]
    }
    def job = new JsonSlurper().parse(new StringReader(created.body))
    def jobUrl = job["url"]?.toString()
    if (!jobUrl) { return [ok: false, final: false, text: "created, but the response carried no job to poll"] }
    if (!isHttps(jobUrl)) {
        return [ok: false, final: true,
                text: "was requested, but the job address Keboola returned does not start with https://"]
    }
    long deadline = Instant.now().toEpochMilli() + timeoutMs
    int pollTimeout = Math.min(timeoutMs, 10000)
    while (Instant.now().toEpochMilli() < deadline) {
        TimeUnit.MILLISECONDS.sleep(Math.min(2000L, Math.max(1L, deadline - Instant.now().toEpochMilli())))
        def status = httpCall(jobUrl, token, "GET", null, null, pollTimeout)
        if (status.status >= 300 && status.status < 400) {
            return [ok: false, final: true, status: status.status, body: status.body,
                    text: withRedirect("was requested, but its creation job could not be read (HTTP " +
                          status.status + ").", status)]
        }
        if (status.status != 200) { continue }
        def state = new JsonSlurper().parse(new StringReader(status.body))
        if (state["status"] == "success") { return [ok: true, text: "created"] }
        if (state["status"] == "error") {
            return [ok: false, final: true,
                    text: "creation job failed: " + JsonOutput.toJson(state["error"]).take(300)]
        }
    }
    return [ok: false, final: false,
            text: "creation job did not finish within " + timeoutMs + " ms (HTTP_TIMEOUT_MS)"]
}

static Message failFinal(Message message, String code, String detail, List notes) {
    message.setProperty("RUN_FAILED", code)
    message.setProperty("RUN_FAILED_DETAIL", detail)
    message.setProperty("MORE_PAGES", "false")
    if (notes) { message.setProperty("DELIVERY_NOTES", notes.join("\n")) }
    return message
}

static void recordKeboolaFault(Object messageLog, int status, String body) {
    if (messageLog == null) { return }
    messageLog.addCustomHeaderProperty("KeboolaStatus", status > 0 ? status.toString() : "none")
    def text = (body ?: "").replaceAll(/\s+/, " ").trim().take(200)
    messageLog.addCustomHeaderProperty("KeboolaError", text ?: "(no body)")
}

def Message processData(Message message) {
    def messageLog = messageLogFactory.getMessageLog(message)
    def notes = []
    def csv = message.getBody(String) ?: ""
    long rowsInPage = 0L
    try { rowsInPage = Long.parseLong(message.getProperty("ROWS_IN_PAGE")?.toString() ?: "0") }
    catch (Exception ignored) { }
    def page = message.getProperty("PAGE_NUMBER")?.toString() ?: "1"
    def loadMode = message.getProperty("DLV_loadMode")?.toString() ?: "full"

    // Empty page
    if (rowsInPage <= 0L) {
        def outcome = "empty"
        if (message.getProperty("RUN_FAILED")?.toString()) {
            outcome = "not delivered"
        } else if (message.getProperty("PAGE_NOTE")?.toString()) {
            outcome = message.getProperty("PAGE_NOTE").toString()
        } else if (loadMode == "full" && !(message.getProperty("FIRST_DATA_PAGE")?.toString()) &&
                   (message.getProperty("MORE_PAGES")?.toString() ?: "false") != "true") {
            outcome = "empty - the table was left as it was"
        }
        if (messageLog != null) { messageLog.addCustomHeaderProperty("PageOutcome", "page " + page + ": " + outcome) }
        return message
    }

    // Settings check
    def tableId = message.getProperty("DLV_tableId")?.toString() ?: ""
    def stackUrl = stripSlash(cfg(message, "CFG_KEBOOLA_STACK_URL", ""))
    if (!tableId || !stackUrl) {
        return failFinal(message, "CONFIG_ERROR",
            "KEBOOLA_TABLE_ID and KEBOOLA_STACK_URL must both be set.", notes)
    }
    def importer = stripSlash(cfg(message, "CFG_KEBOOLA_IMPORTER_URL", ""))
    if (!isHttps(stackUrl)) {
        return failFinal(message, "CONFIG_ERROR", "KEBOOLA_STACK_URL must start with https:// — " +
            "credentials and business data are never sent over an unencrypted connection.", notes)
    }
    if (importer && !isHttps(importer)) {
        return failFinal(message, "CONFIG_ERROR", "KEBOOLA_IMPORTER_URL must start with https:// — " +
            "credentials and business data are never sent over an unencrypted connection.", notes)
    }
    int timeoutMs = 120000
    try { timeoutMs = Integer.parseInt(cfg(message, "CFG_HTTP_TIMEOUT_MS", "120000")) }
    catch (Exception ignored) { }

    // Storage token
    def alias = cfg(message, "CFG_KEBOOLA_CREDENTIAL_ALIAS", "KEBOOLA_STORAGE")
    def store = ITApiFactory.getService(SecureStoreService.class, null)
    if (store == null) {
        return failFinal(message, "CONFIG_ERROR", "The secure store service is not available.", notes)
    }
    def credential = null
    try { credential = store.getUserCredential(alias) } catch (Exception ignored) { credential = null }
    if (credential == null) {
        return failFinal(message, "CONFIG_ERROR",
            "Security material '" + alias + "' (KEBOOLA_CREDENTIAL_ALIAS) was not found in this tenant.", notes)
    }
    def token = new String(credential.getPassword()).trim()

    // Importer address
    if (!importer) {
        def found = discoverImporter(stackUrl, token, timeoutMs, notes)
        importer = found.url
        if (!importer) {
            def where = "Keboola's service index at " + stackUrl + "/v2/storage"
            int status = (int) found.status
            if (status == 200) {
                return failFinal(message, "CONFIG_ERROR",
                    where + " names no importer address; set KEBOOLA_IMPORTER_URL.", notes)
            }
            def detail = withRedirect(where + (status > 0
                ? " answered HTTP " + status + ": " + found.body.take(300).replaceAll(/\s+/, " ")
                : " did not answer: " + found.body.take(120)), found)
            if (status == 401 || status == 403) {
                detail += " — check the Storage token in security material '" + alias + "' (KEBOOLA_CREDENTIAL_ALIAS)"
            }
            if (isFinal(status)) {
                recordKeboolaFault(messageLog, status, found.body)
                return failFinal(message, "KEBOOLA_FAILED", detail, notes)
            }
            throw new IllegalStateException("KEBOOLA_TRANSIENT: " + detail +
                " — the run goes back on the queue and is retried with growing waits.")
        }
        if (!isHttps(importer)) {
            return failFinal(message, "CONFIG_ERROR",
                "Keboola's service index names an importer address that does not start with https://; " +
                "set KEBOOLA_IMPORTER_URL.", notes)
        }
    }

    // Target table
    def firstDataPage = message.getProperty("FIRST_DATA_PAGE")?.toString() ?: "1"
    if (page == firstDataPage) {
        def header = csv.readLines()[0]
        def outcome = ensureTable(stackUrl, token, tableId, header,
                                  message.getProperty("DLV_primaryKey")?.toString() ?: "",
                                  timeoutMs)
        notes << ("table " + tableId + ": " + outcome.text)
        if (!outcome.ok) {
            def detail = "Target table " + tableId + " " + outcome.text
            if (outcome.final) {
                recordKeboolaFault(messageLog, (int) (outcome.status ?: 0), outcome.body ?: detail)
                return failFinal(message, "KEBOOLA_FAILED", detail, notes)
            }
            throw new IllegalStateException("KEBOOLA_TRANSIENT: " + detail +
                " — the run goes back on the queue and is retried with growing waits.")
        }
        if (messageLog != null) { messageLog.addCustomHeaderProperty("TableState", outcome.text) }
    }

    // Page import
    def incremental = (loadMode == "full" && page == firstDataPage) ? "0" : "1"
    def boundary = "----keboola" + UUID.randomUUID()
    def fields = [tableId: tableId, incremental: incremental]
    def result = httpCall(importer + "/write-table", token, "POST",
                          "multipart/form-data; boundary=" + boundary,
                          multipart(boundary, fields, csv.getBytes("UTF-8")), timeoutMs)

    if (result.status < 200 || result.status >= 300) {
        def detail = withRedirect("Keboola import into '" + tableId + "' failed on page " + page +
                     " with status " + result.status + ": " + result.body.take(300).replaceAll(/\s+/, " "), result)
        recordKeboolaFault(messageLog, (int) result.status, result.body)
        if (isFinal((int) result.status)) {
            return failFinal(message, "KEBOOLA_FAILED", detail, notes)
        }
        throw new IllegalStateException("KEBOOLA_TRANSIENT: " + detail +
            " — the run goes back on the queue and is retried with growing waits.")
    }

    // Row count
    long delivered = 0L
    try { delivered = Long.parseLong(message.getProperty("ROWS_DELIVERED")?.toString() ?: "0") }
    catch (Exception ignored) { }
    def imported = rowsInPage
    try {
        def answer = new JsonSlurper().parse(new StringReader(result.body))
        if (answer["importedRowsCount"] != null) {
            imported = ((Number) answer["importedRowsCount"]).longValue()
        }
    } catch (Exception ignored) { }
    message.setProperty("ROWS_DELIVERED", Long.toString(delivered + imported))
    if (notes) {
        def previous = message.getProperty("DELIVERY_NOTES")?.toString() ?: ""
        message.setProperty("DELIVERY_NOTES", (previous ? previous + "\n" : "") + notes.join("\n"))
    }

    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("PageOutcome", "page " + page + ": " + imported + " rows" +
            (incremental == "0" ? " (replaced the table's contents)" : ""))
    }
    return message
}
