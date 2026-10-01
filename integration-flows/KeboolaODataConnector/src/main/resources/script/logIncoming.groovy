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

// The request path with "." and ".." segments resolved, always with one leading slash, so
// that a prefix check cannot be walked around with /sap/opu/odata/../../bc/...
static String normalizePath(String path) {
    def segments = []
    for (String segment : (path ?: "").split("/")) {
        if (!segment || segment == ".") { continue }
        if (segment == "..") {
            if (segments) { segments.remove(segments.size() - 1) }
            continue
        }
        segments << segment
    }
    return "/" + segments.join("/")
}

// CONNECTOR_PATH_PREFIXES: comma separated prefixes; empty means every path is forwarded.
static boolean pathAllowed(String path, String prefixes) {
    def wanted = prefixes?.split(",")?.collect { it.trim() }?.findAll { it } ?: []
    if (!wanted) { return true }
    def normalized = normalizePath(path)
    return wanted.any { prefix ->
        def p = prefix.startsWith("/") ? prefix : "/" + prefix
        (normalized + "/").startsWith(p) || normalized.startsWith(p)
    }
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

    // Path scope
    if (!message.getProperty("KEBOOLA_REJECT_REASON")) {
        def requestPath = headers.get("CamelHttpPath")?.toString() ?: ""
        if (!pathAllowed(requestPath, readCfg(message, "CFG_PATH_PREFIXES"))) {
            message.setProperty("KEBOOLA_REJECT_REASON", "PATH_NOT_ALLOWED")
            message.setProperty("KEBOOLA_REJECT_DETAIL", normalizePath(requestPath))
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
