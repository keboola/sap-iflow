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

// The platform's own NullPointerException is a sign-in fault only when it was thrown while
// the sign-in was prepared (a security material of another type than the method needs);
// the place is the class that threw it. Any other NullPointerException is the catalogue's.
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

    def runId = message.getProperty("CATALOG_RUN_ID")?.toString()
        ?: message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: "unknown"
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()

    // Error message
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
    } else if (signInNotPrepared(errorClass, errorMessage, errorLocation)) {
        status = 500
        code = "CONFIG_ERROR"
        errorMessage = "The sign-in to SAP S/4HANA could not be prepared. " +
            "Check the security material named in S4_CREDENTIAL_ALIAS: it has to exist in this tenant " +
            "and be of the type S4_AUTH_METHOD needs, OAuth2 Client Credentials as shipped, " +
            "User Credentials for Basic. The platform reported: " +
            errorMessage
    } else if (errorClass == "java.lang.NullPointerException") {
        status = 500
        code = "CATALOG_ERROR"
        errorMessage = "The catalogue failed inside the platform at " + (errorLocation ?: "an unknown place") +
            ". The platform reported: " + errorMessage
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
        if (errorLocation) {
            messageLog.setStringProperty("ErrorLocation", errorLocation)
            // Where the platform failed, searchable, for the two codes that mean it did
            if (code == "CATALOG_ERROR" || (code == "CONFIG_ERROR" && !rejectReason
                    && errorMessage.startsWith("The sign-in to SAP S/4HANA could not be prepared"))) {
                messageLog.addCustomHeaderProperty("ErrorLocation", errorLocation.take(200))
            }
        }
        messageLog.addAttachmentAsString("ErrorDetails", errorMessage, "text/plain")
        def incoming = message.getProperty("KEBOOLA_INCOMING_HEADERS")?.toString()
        if (incoming) {
            messageLog.addAttachmentAsString("IncomingHeaders", incoming, "text/plain")
        }
        def notes = message.getProperty("CATALOG_NOTES")
        if (notes instanceof List && notes) {
            String joined = notes.join(" | ")
            messageLog.setStringProperty("CatalogDiagnostics",
                joined.length() > 2000 ? joined.substring(0, 2000) + " ..." : joined)
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
