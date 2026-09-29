import com.sap.gateway.ip.core.customdev.util.Message
import java.util.UUID

static boolean isSensitive(String name, List fragments) {
    def lower = name.toLowerCase()
    return fragments.any { lower.contains(it) }
}

static String redactQuery(String query, List fragments) {
    if (!query) { return query }
    return query.split("&").collect { pair ->
        int i = pair.indexOf("=")
        if (i <= 0) { return pair }
        def name = pair.substring(0, i)
        def decoded = name
        try { decoded = java.net.URLDecoder.decode(name, "UTF-8") } catch (Exception ignored) { }
        return isSensitive(decoded, fragments) ? (name + "=***redacted***") : pair
    }.join("&")
}

def Message processData(Message message) {
    final SENSITIVE_FRAGMENTS = [
        'authorization', 'cookie', 'token', 'secret',
        'password', 'passwd', 'pwd', 'passcode', 'apikey', 'api-key', 'api_key',
        'credential', 'assertion',
    ]
    final ALLOWED_METHODS = ['GET', 'HEAD'] as Set

    def headers = message.getHeaders()

    String runId = message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: UUID.randomUUID().toString()
    message.setProperty("CATALOG_RUN_ID", runId)

    def httpMethod = (headers.get("CamelHttpMethod")?.toString() ?: "GET").toUpperCase()
    if (!ALLOWED_METHODS.contains(httpMethod)) {
        message.setProperty("KEBOOLA_REJECT_REASON", "METHOD_NOT_ALLOWED")
        message.setProperty("KEBOOLA_REJECT_DETAIL", httpMethod)
    }

    // Catalogue route
    def proxyType = message.getProperty("CFG_PROXY_TYPE")?.toString()?.trim()?.toLowerCase() ?: ""
    if (proxyType.contains("{{")) { proxyType = "" }
    def query = headers.get("CamelHttpQuery")?.toString() ?: ""
    message.setProperty("CATALOG_QUERY", query)
    boolean forcedTunnel = query.split("&").any { it == "mode=gateway-cc" }
    message.setProperty("CATALOG_ROUTE", (proxyType == "sapcc" || forcedTunnel) ? "adapter" : "script")

    // Incoming headers
    def summary = headers
        .collect { k, v ->
            String name = k.toString()
            if (isSensitive(name, SENSITIVE_FRAGMENTS)) { return name + ": ***redacted***" }
            if (name.toLowerCase().endsWith("query")) {
                return name + ": " + redactQuery(v?.toString(), SENSITIVE_FRAGMENTS)
            }
            return name + ": " + v
        }
        .sort()
        .join("\n")
    message.setProperty("KEBOOLA_INCOMING_HEADERS", summary)

    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog == null) {
        return message
    }

    messageLog.setStringProperty("RunId", runId)
    messageLog.setStringProperty("HttpMethod", httpMethod)
    messageLog.setStringProperty("QueryString",
        redactQuery(headers.get("CamelHttpQuery")?.toString(), SENSITIVE_FRAGMENTS) ?: "N/A")
    messageLog.setStringProperty("CatalogRoute", message.getProperty("CATALOG_ROUTE")?.toString())

    return message
}
