import com.sap.gateway.ip.core.customdev.util.Message

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

static int toInt(String v, int dflt) {
    try { return (v != null && v.isInteger()) ? v.toInteger() : dflt } catch (Exception ignored) { return dflt }
}

static Map parseQuery(String q) {
    def out = [:]
    if (!q) { return out }
    q.split("&").each { pair ->
        def i = pair.indexOf("=")
        if (i > 0) { out[java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                         java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8") }
    }
    return out
}

static String viewOf(String raw, String configured) {
    def v = (raw ?: "").trim().toLowerCase()
    return (v in ["interfaces", "extended", "all"]) ? v : configured
}

static String odataOf(String raw) {
    def v = (raw ?: "").trim().toLowerCase()
    if (v in ["v2", "2"]) { return "v2" }
    if (v in ["v4", "4"]) { return "v4" }
    return "both"
}

static String choice(String raw, String dflt, List allowed, String name) {
    def v = (raw ?: dflt).trim().toLowerCase()
    if (!(v in allowed)) {
        throw new IllegalStateException("CONFIG: " + name + " must be one of " + allowed.join(", ") +
                                        " - got '" + raw + "'.")
    }
    return v
}

static String arrangementsBase() {
    return "/sap/opu/odata4/sap/aps_com_api_ca_read/srvd_a2x/sap/communicationarrangement/0001"
}

def Message processData(Message message) {
    // Rejected requests
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()
    if (rejectReason) {
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString() ?: "unknown"
        throw new IllegalStateException(
            "HTTP method ${detail} is not supported by this endpoint. Allowed: GET, HEAD.")
    }

    // Address
    def host = readCfg(message, "CFG_S4_HOSTNAME")
    if (!host) {
        throw new IllegalStateException("CONFIG: S4_HOSTNAME is not configured on this integration flow.")
    }
    String lowerHost = host.toLowerCase()
    boolean throughCloudConnector =
        (readCfg(message, "CFG_PROXY_TYPE") ?: "").toLowerCase() == "sapcc"
    if (!lowerHost.startsWith("http://") && !lowerHost.startsWith("https://")) {
        throw new IllegalStateException(
            "CONFIG: S4_HOSTNAME must be written with its protocol, for example " +
            "https://my123456-api.s4hana.cloud.sap, or http://s4hana.virtual:44300 behind an " +
            "SAP Cloud Connector.")
    }
    if (lowerHost.startsWith("http://") && !throughCloudConnector) {
        throw new IllegalStateException(
            "CONFIG: S4_HOSTNAME must start with https:// - credentials and business " +
            "data are never sent over an unencrypted connection.")
    }
    def authMethod = (readCfg(message, "CFG_AUTH_METHOD") ?: "OAuth2 Client Credentials").toLowerCase()
    if (!readCfg(message, "CFG_CREDENTIAL_ALIAS") && authMethod != "client certificate") {
        throw new IllegalStateException("CONFIG: S4_CREDENTIAL_ALIAS is not configured on this integration flow.")
    }

    // Limits and switches
    String cfgTimeout = readCfg(message, "CFG_TIMEOUT_MS")
    int timeoutMs = (cfgTimeout == null) ? 10000 : toInt(cfgTimeout, 0)
    if (timeoutMs < 1) {
        throw new IllegalStateException("CONFIG: HTTP_TIMEOUT_MS must be a whole number of milliseconds " +
                                        "greater than 0; it is '" + cfgTimeout + "'.")
    }
    String view = choice(readCfg(message, "CFG_CATALOG_VIEW"), "interfaces",
                         ["interfaces", "extended", "all"], "CATALOG_VIEW")
    String source = choice(readCfg(message, "CFG_CATALOG_SOURCE"), "auto",
                           ["auto", "arrangements", "gateway"], "CATALOG_SOURCE")
    String resolve = choice(readCfg(message, "CFG_RESOLVE_V4"), "lookup+verify",
                            ["lookup", "lookup+verify", "off"], "CATALOG_RESOLVE_V4")
    String cfgLimit = readCfg(message, "CFG_VERIFY_LIMIT")
    int verifyLimit = (cfgLimit == null) ? 20 : toInt(cfgLimit, -1)
    if (verifyLimit < 0 || verifyLimit > 200) {
        throw new IllegalStateException("CONFIG: CATALOG_VERIFY_LIMIT must be a whole number from 0 to 200; " +
                                        "it is '" + cfgLimit + "'.")
    }
    String diagnostics = choice(readCfg(message, "CFG_DIAGNOSTICS"), "false", ["true", "false"], "CATALOG_DIAGNOSTICS")

    // Address options
    def qs = parseQuery(message.getProperty("CATALOG_QUERY")?.toString())
    message.setProperty("CATALOG_VIEW_EFFECTIVE", viewOf(qs["include"], view))
    message.setProperty("CATALOG_ODATA", odataOf(qs["odata"]))
    boolean debug = diagnostics == "true" && (qs["debug"] == "1" || qs["debug"] == "true")
    message.setProperty("CATALOG_DEBUG", debug ? "true" : "false")
    def sapClient = readCfg(message, "CFG_SAP_CLIENT")
    message.setProperty("CATALOG_QUERY_EXTRA",
        sapClient ? "sap-client=" + java.net.URLEncoder.encode(sapClient, "UTF-8") : "")

    // The plan
    message.setProperty("CATALOG_T0", java.time.Instant.now().toEpochMilli().toString())
    message.setProperty("CATALOG_HOST", host.replaceAll("/+\$", ""))
    message.setProperty("CATALOG_SOURCE_MODE", source)
    message.setProperty("CATALOG_RESOLVE_MODE", resolve)
    message.setProperty("CATALOG_VERIFY_LIMIT_N", verifyLimit.toString())
    message.setProperty("CATALOG_TRY_ARRANGEMENTS", source == "gateway" ? "false" : "true")
    message.setProperty("CATALOG_TRY_GATEWAY", source == "gateway" ? "true" : "false")
    message.setProperty("CATALOG_NOTES", [])
    message.setProperty("GW_RAN", "false")

    // The arrangement loop
    message.setProperty("ARR_MORE", source == "gateway" ? "false" : "true")
    message.setProperty("ARR_SET", "0")
    message.setProperty("ARR_PAGE", "0")
    message.setProperty("ARR_CALLS", "0")
    message.setProperty("ARR_OUTCOME", "")
    message.setProperty("ARR_FIRST_STATUS", "")
    message.setProperty("ARR_NEXT_PATH", arrangementsBase() + "/CommunicationArrangements")
    message.setProperty("ARR_NEXT_QUERY", "")
    message.setProperty("ARR_ARRANGEMENTS", [])
    message.setProperty("ARR_INBOUND", [])
    message.setProperty("ARR_USERS", [])

    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.setStringProperty("CatalogPlan", "source " + source + ", resolve " + resolve +
            ", verify limit " + verifyLimit + ", view " + view + ", diagnostics " + diagnostics)
    }
    return message
}
