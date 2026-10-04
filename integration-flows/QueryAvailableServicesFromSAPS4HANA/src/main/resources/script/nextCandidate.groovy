import com.sap.gateway.ip.core.customdev.util.Message

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

static int toInt(String v, int dflt) {
    try { return (v != null && v.isInteger()) ? v.toInteger() : dflt } catch (Exception ignored) { return dflt }
}

def Message processData(Message message) {
    // Target path: the candidate's service document, one small call
    def queue = message.getProperty("VERIFY_QUEUE")
    if (!(queue instanceof List)) { queue = [] }
    int index = toInt(message.getProperty("VERIFY_INDEX")?.toString(), 0)
    def candidate = index < queue.size() ? queue[index] : [name: "", path: ""]
    String path = candidate.path + "/"
    String hostRaw = readCfg(message, "CFG_S4_HOSTNAME") ?: ""
    message.setProperty("S4_TARGET_PATH", hostRaw.endsWith("/") ? path.substring(1) : path)
    message.setProperty("S4_QUERY_STRING", message.getProperty("CATALOG_QUERY_EXTRA")?.toString() ?: "")
    message.setProperty("VERIFY_CALL_T0", java.time.Instant.now().toEpochMilli().toString())

    // A clean request: nothing of the previous answer travels on
    ["CamelHttpPath", "CamelHttpQuery", "CamelHttpUri", "CamelHttpUrl", "CamelHttpResponseCode",
     "CamelHttpResponseText", "Content-Type", "Content-Length", "Content-Encoding", "Content-Language",
     "ETag", "Last-Modified", "Cache-Control", "DataServiceVersion", "OData-Version", "sap-message",
     "sap-messagescount", "Location", "Retry-After"].each { message.setHeader(it, null) }
    message.setHeader("CamelHttpMethod", "GET")
    message.setHeader("Accept", "application/json")
    message.setBody("")

    // SAP integration key
    def airKey = readCfg(message, "CFG_AIR_KEY")
    def airHeaderName = readCfg(message, "CFG_AIR_HEADER_NAME")
    if (airKey && airHeaderName) { message.setHeader(airHeaderName, airKey) }
    return message
}
