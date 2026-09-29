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

static boolean signInNotPrepared(String errorClass, String errorMessage) {
    if (errorMessage.contains("No artifact descriptor found")) { return true }
    if (errorMessage.contains("Could not find credential")) { return true }
    return errorClass == "java.lang.NullPointerException"
}

def Message processData(Message message) {
    def ex = message.getProperty("CamelExceptionCaught")
    def messageLog = messageLogFactory.getMessageLog(message)

    def runId = message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: "unknown"
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()

    // Exception details
    def errorMessage = "Unknown error"
    def errorClass = "Unknown"
    if (ex != null) {
        errorMessage = ex.getMessage() ?: "No message"
        errorClass = ex.getClass().getName()
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
    } else if (signInNotPrepared(errorClass, errorMessage)) {
        status = 500
        code = "CONFIG_ERROR"
        errorMessage = "The sign-in to SAP S/4HANA could not be prepared. " +
            "Check the security material named in S4_CREDENTIAL_ALIAS: it has to exist in this tenant " +
            "and be of the type the authentication method of the SAP S/4HANA receiver needs, OAuth2 " +
            "Client Credentials as shipped, User Credentials for Basic. The platform reported: " + errorMessage
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
