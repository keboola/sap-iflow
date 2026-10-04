import com.sap.gateway.ip.core.customdev.util.Message

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

def Message processData(Message message) {
    // V2 catalogue answer
    def status = message.getHeaders().get("CamelHttpResponseCode")?.toString() ?: "0"
    message.setProperty("GW_V2_STATUS", status)
    message.setProperty("GW_V2_BODY", message.getBody(String) ?: "")

    def query = "\$expand=DefaultSystem(\$expand=Services)"
    def extra = message.getProperty("CATALOG_QUERY_EXTRA")?.toString() ?: ""
    if (extra) { query += "&" + extra }

    // Target path
    String path = "/sap/opu/odata4/iwfnd/config/default/iwfnd/catalog/0002/ServiceGroups"
    String hostRaw = readCfg(message, "CFG_S4_HOSTNAME") ?: ""
    message.setProperty("S4_TARGET_PATH", hostRaw.endsWith("/") ? path.substring(1) : path)
    message.setProperty("S4_QUERY_STRING", query)

    // The V2 answer's headers are cleared before the V4 call: nothing of one answer travels on
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
