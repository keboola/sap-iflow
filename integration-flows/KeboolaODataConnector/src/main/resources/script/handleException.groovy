import com.sap.gateway.ip.core.customdev.util.Message

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

static String jsonEscape(String s) {
    if (s == null) { return "" }
    return s.replace('\\', '\\\\')
            .replace('"', '\\"')
            .replace('\n', '\\n')
            .replace('\r', '\\r')
            .replace('\t', '\\t')
            .replaceAll('[\\x00-\\x1F]', ' ')
}

// The platform's own NullPointerException is a sign-in fault only when it was thrown while
// the sign-in was prepared (a security material of another type than the method needs);
// the place is the class that threw it. Any other NullPointerException is the connector's.
static boolean signInLocation(String errorLocation) {
    def where = (errorLocation ?: "").toLowerCase()
    return ["auth", "credential", "securestore", "security", "oauth"].any { where.contains(it) }
}

static boolean signInNotPrepared(String errorClass, String errorMessage, String errorLocation) {
    // The platform's own words for a security material that does not exist: the first two
    // under OAuth2 Client Credentials, the third under Basic (measured 2026-09-30).
    if (errorMessage.contains("No artifact descriptor found")) { return true }
    if (errorMessage.contains("Could not find credential")) { return true }
    if (errorMessage.contains("No credentials for")) { return true }
    return errorClass == "java.lang.NullPointerException" && signInLocation(errorLocation)
}

def Message processData(Message message) {
    def ex = message.getProperty("CamelExceptionCaught")
    def messageLog = messageLogFactory.getMessageLog(message)

    def runId = message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: "unknown"
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()

    // Exception details
    def errorMessage = "Unknown error"
    def errorClass = "Unknown"
    def errorLocation = ""
    if (ex != null) {
        errorMessage = ex.getMessage() ?: "No message"
        errorClass = ex.getClass().getName()
        def frames = ex instanceof Throwable ? ex.getStackTrace() : null
        if (frames && frames.length > 0) {
            errorLocation = frames[0].getClassName() + "." + frames[0].getMethodName()
        }
    }
    errorMessage = errorMessage.replaceAll(/^(?:[A-Za-z0-9_.]+Exception:\s*)+/, "")
                               .replaceAll(/(?s)@ line \d+ in .*$/, "").trim()
    if (!errorMessage) { errorMessage = "Unknown error" }

    // Status and error code
    int status
    String code
    if (rejectReason == "METHOD_NOT_ALLOWED") {
        status = 405
        code = "METHOD_NOT_ALLOWED"
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString()
        if (detail) {
            errorMessage = "HTTP method ${detail} is not supported by this connector. Allowed: GET, HEAD."
        }
    } else if (rejectReason == "CONFIG_ERROR") {
        status = 500
        code = "CONFIG_ERROR"
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString()
        if (detail) { errorMessage = detail }
    } else if (rejectReason == "PATH_NOT_ALLOWED") {
        status = 403
        code = "PATH_NOT_ALLOWED"
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString() ?: "the requested path"
        errorMessage = "The path " + detail + " is outside the paths this connector forwards to SAP S/4HANA. " +
            "CONNECTOR_PATH_PREFIXES allows " + (readCfg(message, "CFG_PATH_PREFIXES") ?: "(nothing)") +
            "; add a prefix there, or leave it empty to forward every path."
    } else if (signInNotPrepared(errorClass, errorMessage, errorLocation)) {
        status = 500
        code = "CONFIG_ERROR"
        errorMessage = "The sign-in to SAP S/4HANA could not be prepared. " +
            "Check the security material named in S4_CREDENTIAL_ALIAS: it has to exist in this tenant " +
            "and be of the type S4_AUTH_METHOD needs, OAuth2 Client Credentials as shipped, " +
            "User Credentials for Basic. The platform reported: " + errorMessage
    } else if (errorClass == "java.lang.NullPointerException") {
        status = 500
        code = "CONNECTOR_ERROR"
        errorMessage = "The connector failed inside the platform at " + (errorLocation ?: "an unknown place") +
            ". The platform reported: " + errorMessage
    } else if (errorClass.toLowerCase().contains("timeout") || errorMessage.toLowerCase().contains("timed out")
               || errorMessage.toLowerCase().contains("timeout")) {
        status = 504
        code = "UPSTREAM_TIMEOUT"
    } else {
        status = 502
        code = "UPSTREAM_UNREACHABLE"
    }

    message.setProperty("SAP_MessageProcessingLogCustomStatus", code)

    // Monitor properties
    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("UpstreamOutcome", "FAILED")
        messageLog.addCustomHeaderProperty("UpstreamStatus", status.toString())
        messageLog.setStringProperty("UpstreamOutcome", "FAILED")
        messageLog.setStringProperty("UpstreamStatus", status.toString())
        messageLog.setStringProperty("ErrorClass", errorClass)
        if (errorLocation) {
            messageLog.setStringProperty("ErrorLocation", errorLocation)
            // Where the platform failed, searchable, for the two codes that mean it did
            if (code == "CONNECTOR_ERROR" || (code == "CONFIG_ERROR" && !rejectReason)) {
                messageLog.addCustomHeaderProperty("ErrorLocation", errorLocation.take(200))
            }
        }
        messageLog.addAttachmentAsString("ErrorDetails", errorMessage, "text/plain")
        def incoming = message.getProperty("KEBOOLA_INCOMING_HEADERS")?.toString()
        if (incoming) {
            messageLog.addAttachmentAsString("IncomingHeaders", incoming, "text/plain")
        }
    }

    // Error body
    def timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
            .format(java.time.Instant.now().atZone(java.time.ZoneId.systemDefault()))
    def body = """{
  "error": {
    "code": "${code}",
    "message": "${jsonEscape(errorMessage)}",
    "messageId": "${jsonEscape(runId)}",
    "timestamp": "${timestamp}"
  }
}"""

    message.setBody(body)
    message.setHeader("CamelHttpResponseCode", status)
    message.setHeader("Content-Type", "application/json")

    if (status == 405) {
        message.setHeader("Allow", "GET, HEAD")
    }

    // SAP integration key
    def airHeader = readCfg(message, "CFG_AIR_HEADER_NAME")
    if (airHeader) {
        message.setHeader(airHeader, null)
    }

    return message
}
