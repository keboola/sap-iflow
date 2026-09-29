import com.sap.gateway.ip.core.customdev.util.Message

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

    def runId = message.getProperty("CATALOG_RUN_ID")?.toString()
        ?: message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: "unknown"
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()

    // Error message
    def errorMessage = "Unknown error"
    def errorClass = "Unknown"
    if (ex != null) {
        errorMessage = ex.getMessage() ?: "No message"
        errorClass = ex.getClass().getName()
    }
    errorMessage = errorMessage.replaceAll(/^(?:[A-Za-z0-9_.]+Exception:\s*)+/, "")
                               .replaceAll(/(?s)@ line \d+ in .*$/, "").trim()
    if (!errorMessage) { errorMessage = "Unknown error" }

    // Error code
    int status
    String code
    if (rejectReason == "METHOD_NOT_ALLOWED") {
        status = 405
        code = "METHOD_NOT_ALLOWED"
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString()
        if (detail) {
            errorMessage = "HTTP method ${detail} is not supported by this endpoint. Allowed: GET, HEAD."
        }
    } else if (errorMessage.startsWith("CONFIG:")) {
        status = 500
        code = "CONFIG_ERROR"
        errorMessage = errorMessage.replaceFirst(/CONFIG:\s*/, "")
    } else if (errorMessage.startsWith("UPSTREAM:")) {
        status = 502
        code = "UPSTREAM_ERROR"
        errorMessage = errorMessage.replaceFirst(/UPSTREAM:\s*/, "")
    } else if (signInNotPrepared(errorClass, errorMessage)) {
        status = 500
        code = "CONFIG_ERROR"
        errorMessage = "The sign-in to SAP S/4HANA could not be prepared. " +
            "Check the security material named in S4_CREDENTIAL_ALIAS: it has to exist in this tenant " +
            "and be of the type the authentication method of the receivers HTTP_V2 and HTTP_V4 needs, " +
            "OAuth2 Client Credentials as shipped, User Credentials for Basic. The platform reported: " +
            errorMessage
    } else if (errorClass.toLowerCase().contains("timeout") || errorMessage.toLowerCase().contains("timed out")) {
        status = 504
        code = "UPSTREAM_TIMEOUT"
    } else if (errorClass.contains("IOException") || errorClass.contains("ConnectException")
               || errorClass.contains("UnknownHost")
               || errorMessage.toLowerCase().contains("cloud connector")
               || errorMessage.toLowerCase().contains("connection refused")
               || errorMessage.toLowerCase().contains("unknownhost")
               || errorMessage.toLowerCase().contains("no route to host")) {
        status = 502
        code = "UPSTREAM_UNREACHABLE"
    } else {
        status = 500
        code = "CATALOG_ERROR"
    }

    message.setProperty("SAP_MessageProcessingLogCustomStatus", code)

    if (messageLog != null) {
        messageLog.setStringProperty("ErrorClass", errorClass)
        messageLog.setStringProperty("RunId", runId)
        messageLog.setStringProperty("ResponseStatus", status.toString())
        messageLog.addCustomHeaderProperty("CatalogSource", "error")
        messageLog.addCustomHeaderProperty("UpstreamOutcome", "FAILED")
        messageLog.addCustomHeaderProperty("UpstreamStatus", status.toString())
        messageLog.addAttachmentAsString("ErrorDetails", errorMessage, "text/plain")
        def incoming = message.getProperty("KEBOOLA_INCOMING_HEADERS")?.toString()
        if (incoming) {
            messageLog.addAttachmentAsString("IncomingHeaders", incoming, "text/plain")
        }
    }

    // SAP integration key
    def airHeaderName = message.getProperty("CFG_AIR_HEADER_NAME")?.toString()?.trim()
    if (airHeaderName && !airHeaderName.contains("{{")) {
        message.setHeader(airHeaderName, null)
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

    return message
}
