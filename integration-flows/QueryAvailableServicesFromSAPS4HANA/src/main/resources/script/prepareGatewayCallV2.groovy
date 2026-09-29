import com.sap.gateway.ip.core.customdev.util.Message

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

def Message processData(Message message) {
    // Rejected requests
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()
    if (rejectReason) {
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString() ?: "unknown"
        throw new IllegalStateException(
            "HTTP method ${detail} is not supported by this endpoint. Allowed: GET, HEAD.")
    }
    def host = readCfg(message, "CFG_S4_HOSTNAME")
    if (!host) {
        throw new IllegalStateException("CONFIG: S4_HOSTNAME is not configured on this integration flow.")
    }
    String lowerHost = host.toLowerCase()
    if (!lowerHost.startsWith("http://") && !lowerHost.startsWith("https://")) {
        throw new IllegalStateException(
            "CONFIG: S4_HOSTNAME must be written with its protocol, for example " +
            "http://s4hana.virtual:44300 behind an SAP Cloud Connector.")
    }
    boolean throughCloudConnector =
        (readCfg(message, "CFG_PROXY_TYPE") ?: "").toLowerCase() == "sapcc"
    if (lowerHost.startsWith("http://") && !throughCloudConnector) {
        throw new IllegalStateException(
            "CONFIG: S4_HOSTNAME must start with https:// - credentials and business " +
            "data are never sent over an unencrypted connection.")
    }
    if (!readCfg(message, "CFG_CREDENTIAL_ALIAS")) {
        throw new IllegalStateException("CONFIG: S4_CREDENTIAL_ALIAS is not configured on this integration flow.")
    }

    def query = "\$format=json"
    def client = readCfg(message, "CFG_SAP_CLIENT")
    if (client) { query += "&sap-client=" + client }

    // Target path
    String path = "/sap/opu/odata/IWFND/CATALOGSERVICE;v=2/ServiceCollection"
    message.setProperty("S4_TARGET_PATH", host.endsWith("/") ? path.substring(1) : path)
    message.setProperty("S4_QUERY_STRING", query)
    message.setProperty("CATALOG_T0", java.time.Instant.now().toEpochMilli().toString())

    message.setHeader("CamelHttpPath", null)
    message.setHeader("CamelHttpQuery", null)
    message.setHeader("CamelHttpUri", null)
    message.setHeader("CamelHttpUrl", null)
    message.setHeader("CamelHttpMethod", "GET")
    message.setBody("")

    // SAP integration key
    def airKey = readCfg(message, "CFG_AIR_KEY")
    def airHeaderName = readCfg(message, "CFG_AIR_HEADER_NAME")
    if (airKey && airHeaderName) { message.setHeader(airHeaderName, airKey) }
    return message
}
