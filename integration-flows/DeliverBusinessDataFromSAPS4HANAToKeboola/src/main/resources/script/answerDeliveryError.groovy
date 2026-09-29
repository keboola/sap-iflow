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

def Message processData(Message message) {
    // Error text
    def ex = message.getProperty("CamelExceptionCaught")
    def text = "Unknown error"
    try { text = ex?.getMessage() ?: text } catch (Exception ignored) { }
    text = text.replaceAll(/^(?:[A-Za-z0-9_.]+Exception:\s*)+/, "")
               .replaceAll(/(?s)@ line \d+ in .*$/, "").trim()

    // Status code
    int status = 500
    String code = "DELIVERY_NOT_STARTED"
    if (text.startsWith("METHOD:")) {
        status = 405
        code = "METHOD_NOT_ALLOWED"
        text = "HTTP method " + text.substring("METHOD:".length()).trim() +
               " is not supported by this endpoint. Allowed: POST."
    } else if (text.startsWith("CONFIG:")) {
        code = "CONFIG_ERROR"
        text = text.substring("CONFIG:".length()).trim()
    }
    if (!text) { text = "Unknown error" }

    // Error body
    def runId = message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: "unknown"
    def timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
            .format(java.time.Instant.now().atZone(java.time.ZoneId.systemDefault()))

    message.setProperty("SAP_MessageProcessingLogCustomStatus", code)
    message.setBody("""{
  "error": {
    "code": "${code}",
    "message": "${jsonEscape(text)}",
    "messageId": "${jsonEscape(runId)}",
    "timestamp": "${timestamp}"
  }
}""")
    message.setHeader("CamelHttpResponseCode", status)
    message.setHeader("Content-Type", "application/json")
    if (status == 405) {
        message.setHeader("Allow", "POST")
    }

    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("UpstreamOutcome", "FAILED")
        messageLog.addCustomHeaderProperty("UpstreamStatus", status.toString())
        messageLog.setStringProperty("ErrorClass", ex != null ? ex.getClass().getName() : "Unknown")
        messageLog.addAttachmentAsString("ErrorDetails", text, "text/plain")
    }
    return message
}
