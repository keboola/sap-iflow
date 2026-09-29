import com.sap.gateway.ip.core.customdev.util.Message

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

def Message processData(Message message) {
    // Rejected requests
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()
    if (rejectReason == "METHOD_NOT_ALLOWED") {
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString() ?: "unknown"
        throw new IllegalStateException(
            "HTTP method ${detail} is not supported by this connector. Allowed: GET, HEAD.")
    } else if (rejectReason) {
        throw new IllegalStateException(
            "CONFIG: " + (message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString()
                          ?: "this integration flow is not configured correctly."))
    }

    def headers = message.getHeaders()

    // Target path
    def fullPath = headers.get("CamelHttpPath")?.toString() ?: ""
    def rawUrl = headers.get("CamelHttpUrl")?.toString() ?: ""
    if (rawUrl.contains("%")) {
        try {
            def rawPath = new java.net.URL(rawUrl).getPath() ?: ""
            def wanted = fullPath.startsWith("/") ? fullPath : "/" + fullPath
            for (int i = 0; i < rawPath.length(); i++) {
                def tail = rawPath.substring(i)
                if (!tail.startsWith("/")) { continue }
                if (java.net.URLDecoder.decode(tail.replace("+", "%2B"), "UTF-8") == wanted) {
                    fullPath = tail
                    break
                }
            }
        } catch (Exception ignored) { }
    }
    if (fullPath.startsWith("/")) {
        fullPath = fullPath.substring(1)
    }

    def httpMethod = (headers.get("CamelHttpMethod")?.toString() ?: "GET").toUpperCase()
    message.setProperty("S4_HTTP_METHOD", httpMethod)

    def baseAddress = readCfg(message, "CFG_S4_HOSTNAME") ?: ""
    message.setProperty("S4_TARGET_PATH", (baseAddress.endsWith("/") ? "" : "/") + fullPath)

    // SAP client
    def query = headers.get("CamelHttpQuery")?.toString() ?: ""
    def sapClient = readCfg(message, "CFG_SAP_CLIENT")
    if (sapClient && !(query =~ /(?i)(^|&)sap-client=/)) {
        query = (query ? query + "&" : "") + "sap-client=" +
                java.net.URLEncoder.encode(sapClient, "UTF-8").replace("+", "%20")
    }
    message.setProperty("S4_QUERY_STRING", query)

    // SAP integration key
    def airKey = readCfg(message, "CFG_AIR_KEY")
    def airHeader = readCfg(message, "CFG_AIR_HEADER_NAME")
    if (airKey && airHeader) {
        message.setHeader(airHeader, airKey)
    }

    message.setHeader("CamelHttpPath", null)
    message.setHeader("CamelHttpQuery", null)

    return message
}
