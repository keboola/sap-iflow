import com.sap.gateway.ip.core.customdev.util.Message

static String excerpt(Message message) {
    def body = ""
    try { body = message.getBody(String) ?: "" }
    catch (Exception e) { body = "(response body could not be read: ${e.getMessage()})" }
    if (body.length() > 8192) { body = body.substring(0, 8192) + "\n… truncated" }
    return body
}

static String contentTypeOf(Map headers) {
    def entry = headers.find { it.key?.toString()?.equalsIgnoreCase("Content-Type") }
    return entry?.value?.toString()?.trim() ?: ""
}

static void record(Object messageLog, int status, String outcome, String body) {
    if (messageLog == null) { return }
    messageLog.addCustomHeaderProperty("UpstreamStatus", status > 0 ? status.toString() : "unknown")
    messageLog.addCustomHeaderProperty("UpstreamOutcome", outcome)
    messageLog.addCustomHeaderProperty("UpstreamError",
        body.take(200).replaceAll(/\s+/, " ").trim() ?: "(no body)")
}

static Message endRun(Message message, String detail) {
    message.setProperty("PAGE_VERDICT", "failed")
    message.setProperty("RUN_FAILED", "UPSTREAM_FAILED")
    message.setProperty("RUN_FAILED_DETAIL", detail)
    message.setProperty("MORE_PAGES", "false")
    message.setBody("")
    return message
}

def Message processData(Message message) {
    def headers = message.getHeaders()
    def messageLog = messageLogFactory.getMessageLog(message)
    def page = message.getProperty("PAGE_NUMBER")?.toString() ?: "?"

    // Response status
    int status = 0
    try {
        def raw = headers.get("CamelHttpResponseCode")
        status = raw != null ? Integer.parseInt(raw.toString()) : 0
    } catch (NumberFormatException ignored) { status = 0 }

    // Successful answer
    if (status >= 200 && status < 300) {
        def contentType = contentTypeOf(headers)
        def kind = contentType.toLowerCase()
        int parameters = kind.indexOf(";")
        if (parameters >= 0) { kind = kind.substring(0, parameters) }
        if (!kind.contains("html") && !kind.contains("xml")) {
            message.setProperty("PAGE_VERDICT", "ok")
            return message
        }
        record(messageLog, status, "FAILED", excerpt(message))
        return endRun(message, "SAP S/4HANA answered page " + page + " with " + contentType.take(100) +
            " instead of JSON. S4_HOSTNAME probably names a sign-in page or a web front end " +
            "rather than the API address of the system.")
    }

    // Failed answer
    boolean transientFailure = status == 0 || status >= 500 || status == 429 || status == 408
    def body = excerpt(message)
    def detail = "HTTP " + (status > 0 ? status.toString() : "(no status)") +
                 " from SAP S/4HANA on page " + page + ": " + body.take(300).replaceAll(/\s+/, " ")

    record(messageLog, status, transientFailure ? "RETRY" : "FAILED", body)

    if (transientFailure) {
        throw new IllegalStateException("UPSTREAM_TRANSIENT: " + detail +
            " — the run goes back on the queue and is retried with growing waits.")
    }
    return endRun(message, detail)
}
