import com.sap.gateway.ip.core.customdev.util.Message

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

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

def Message processData(Message message) {
    final SENSITIVE_FRAGMENTS = [
        'authorization', 'cookie', 'token', 'secret',
        'password', 'passwd', 'pwd', 'passcode', 'apikey', 'api-key', 'api_key',
        'credential', 'assertion',
    ]
    final ALLOWED_METHODS = ['GET', 'HEAD'] as Set

    def headers = message.getHeaders()

    // Method and host checks
    def httpMethod = (headers.get("CamelHttpMethod")?.toString() ?: "GET").toUpperCase()
    if (!ALLOWED_METHODS.contains(httpMethod)) {
        message.setProperty("KEBOOLA_REJECT_REASON", "METHOD_NOT_ALLOWED")
        message.setProperty("KEBOOLA_REJECT_DETAIL", httpMethod)
    } else {
        def host = readCfg(message, "CFG_S4_HOSTNAME")
        boolean throughCloudConnector =
            (readCfg(message, "CFG_PROXY_TYPE") ?: "").toLowerCase() == "sapcc"
        boolean tunnelAddress = throughCloudConnector && host != null &&
            host.toLowerCase().startsWith("http://")
        if (!host) {
            message.setProperty("KEBOOLA_REJECT_REASON", "CONFIG_ERROR")
            message.setProperty("KEBOOLA_REJECT_DETAIL",
                "S4_HOSTNAME is not configured on this integration flow.")
        } else if (throughCloudConnector && !tunnelAddress &&
                   !host.toLowerCase().startsWith("https://")) {
            message.setProperty("KEBOOLA_REJECT_REASON", "CONFIG_ERROR")
            message.setProperty("KEBOOLA_REJECT_DETAIL",
                "S4_HOSTNAME must start with http:// followed by the virtual host defined in " +
                "the SAP Cloud Connector, for example http://s4hana.virtual:44300.")
        } else if (!tunnelAddress && !host.toLowerCase().startsWith("https://")) {
            message.setProperty("KEBOOLA_REJECT_REASON", "CONFIG_ERROR")
            message.setProperty("KEBOOLA_REJECT_DETAIL",
                "S4_HOSTNAME must start with https:// — business data and credentials are " +
                "never sent over an unencrypted connection. Configured value starts with '" +
                host.substring(0, Math.min(host.length(), 8)) + "'.")
        }
    }

    // Masked header summary
    def summary = headers
        .collect { k, v ->
            def name = k.toString()
            if (isSensitive(name, SENSITIVE_FRAGMENTS)) {
                return "${k}: ***redacted***"
            }
            def shown = name.toLowerCase().endsWith('query') ?
                redactQuery(v?.toString(), SENSITIVE_FRAGMENTS) : v
            return "${k}: ${shown}"
        }
        .sort()
        .join("\n")
    message.setProperty("KEBOOLA_INCOMING_HEADERS", summary)

    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog == null) {
        return message
    }

    // Monitor properties
    def path = headers.get("CamelHttpPath")?.toString() ?: "N/A"
    messageLog.addCustomHeaderProperty("HttpPath", path.take(200))
    messageLog.setStringProperty("HttpMethod", httpMethod)
    messageLog.setStringProperty("HttpPath", path)
    messageLog.setStringProperty("QueryString",
        redactQuery(headers.get("CamelHttpQuery")?.toString(), SENSITIVE_FRAGMENTS) ?: "N/A")

    return message
}
