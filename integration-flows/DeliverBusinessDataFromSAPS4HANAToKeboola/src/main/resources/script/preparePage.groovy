import com.sap.gateway.ip.core.customdev.util.Message

static String cfg(Message message, String key, String fallback) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return fallback }
    return v
}

static String encode(String s) {
    return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

static String truncate(String timestamp, String precision) {
    if (!timestamp || precision != "day" || timestamp.length() < 10) { return timestamp }
    return timestamp.substring(0, 10) + "T00:00:00"
}

static String lowerBound(String watermark, String precision) {
    String start = truncate(watermark, precision)
    if (precision != "day" || !start || start.length() < 10) { return start }
    try {
        return java.time.LocalDate.parse(start.substring(0, 10)).minusDays(1).toString() + "T00:00:00"
    } catch (Exception ignored) {
        return start
    }
}

static String literal(String timestamp, String fieldType, boolean v4) {
    if (v4) {
        return fieldType == "date" ? timestamp.substring(0, 10) : (timestamp + "Z")
    }
    if (fieldType == "datetimeoffset") { return "datetimeoffset'" + timestamp + "Z'" }
    if (fieldType == "date") { return "datetime'" + timestamp.substring(0, 10) + "T00:00:00'" }
    return "datetime'" + timestamp + "'"
}

static String deltaClause(String field, String watermark, String runStartedAt,
                          String fieldType, String precision, boolean v4) {
    def names = (field ?: "").split(",").collect { it.trim() }.findAll { it }
    def windows = names.collect { name ->
        def parts = []
        if (watermark) { parts << (name + " ge " + literal(lowerBound(watermark, precision), fieldType, v4)) }
        if (runStartedAt) { parts << (name + " le " + literal(truncate(runStartedAt, precision), fieldType, v4)) }
        return parts.join(" and ")
    }.findAll { it }
    if (windows.size() <= 1) { return windows ? windows[0] : "" }
    return windows.collect { "(" + it + ")" }.join(" or ")
}

static Map resolveLink(String link, String servicePath) {
    int cut = link.indexOf("?")
    String before = cut >= 0 ? link.substring(0, cut) : link
    String query = cut >= 0 ? link.substring(cut + 1) : ""
    String path
    if (before ==~ /(?i)https?:\/\/.*/) {
        try { path = new java.net.URL(before).getPath() ?: "/" }
        catch (Exception ignored) { path = before.replaceFirst(/(?i)^https?:\/\/[^\/]+/, "") }
    } else if (before.startsWith("/")) {
        path = before
    } else {
        path = servicePath + "/" + before
    }
    if (!query.contains("%24format=") && !query.contains("\$format=")) {
        query = (query ? query + "&" : "") + "%24format=json"
    }
    return [path: path, query: query]
}

static String withSapClient(String query, String client) {
    if (!client || query =~ /(?i)(^|&)sap-client=/) { return query }
    return (query ? query + "&" : "") + "sap-client=" + encode(client)
}

static String afterHost(String path, boolean hostEndsWithSlash) {
    return (hostEndsWithSlash && path.startsWith("/")) ? path.substring(1) : path
}

def Message processData(Message message) {
    // Inbound headers
    message.setHeader("CamelHttpPath", null)
    message.setHeader("CamelHttpQuery", null)
    message.setHeader("CamelHttpUri", null)
    message.setHeader("CamelHttpUrl", null)

    // Page number
    long page = Long.parseLong(message.getProperty("PAGE_NUMBER")?.toString() ?: "0") + 1L
    message.setProperty("PAGE_NUMBER", Long.toString(page))
    message.setProperty("PAGE_VERDICT", "ok")
    message.setProperty("PAGE_KEY",
        (message.getProperty("KEBOOLA_MESSAGE_ID")?.toString() ?: "unknown") + "-" + page)

    // SAP integration key
    def airKey = cfg(message, "CFG_AIR_KEY", "")
    def airHeader = cfg(message, "CFG_AIR_HEADER_NAME", "")
    if (airKey && airHeader) { message.setHeader(airHeader, airKey) }

    def servicePath = message.getProperty("DLV_servicePath")?.toString() ?: ""
    def entitySet = message.getProperty("DLV_entitySet")?.toString() ?: ""
    boolean v4 = servicePath.contains("/odata4/")
    def sapClient = cfg(message, "CFG_SAP_CLIENT", "")
    boolean hostEndsWithSlash = cfg(message, "CFG_S4_HOSTNAME", "").endsWith("/")

    // Next page link
    def nextLink = message.getProperty("NEXT_LINK")?.toString() ?: ""
    message.setProperty("PAGE_ASKED_BY_LINK", nextLink ? "true" : "false")
    if (nextLink) {
        def target = resolveLink(nextLink, servicePath)
        message.setProperty("S4_TARGET_PATH", afterHost(target.path, hostEndsWithSlash))
        message.setProperty("S4_QUERY_STRING", withSapClient(target.query, sapClient))
        return message
    }

    // Query options
    def query = []
    query << "%24format=json"
    query << ("%24top=" + (message.getProperty("DLV_pageSize")?.toString() ?: "1000"))
    def skip = message.getProperty("NEXT_SKIP")?.toString() ?: "0"
    if (skip && skip != "0") { query << ("%24skip=" + skip) }
    def select = message.getProperty("DLV_select")?.toString() ?: ""
    if (select) { query << ("%24select=" + encode(select)) }

    // Sort order
    def primaryKey = message.getProperty("DLV_primaryKey")?.toString() ?: ""
    if (primaryKey) {
        def keys = primaryKey.split(",").collect { it.trim() }.findAll { it }
        if (keys) { query << ("%24orderby=" + encode(keys.join(","))) }
    }

    // Filters
    def filters = []
    def filter = message.getProperty("DLV_filter")?.toString() ?: ""
    if (filter) { filters << ("(" + filter + ")") }
    if ((message.getProperty("DLV_loadMode")?.toString() ?: "full") == "incremental") {
        def clause = deltaClause(message.getProperty("DLV_deltaField")?.toString(),
                                 message.getProperty("DLV_watermark")?.toString(),
                                 message.getProperty("DLV_runStartedAt")?.toString(),
                                 message.getProperty("DLV_deltaFieldType")?.toString() ?: "datetime",
                                 message.getProperty("DLV_deltaPrecision")?.toString() ?: "day", v4)
        if (clause) {
            filters << ("(" + clause + ")")
            if (page == 1L) {
                def messageLog = messageLogFactory.getMessageLog(message)
                if (messageLog != null) { messageLog.addCustomHeaderProperty("DeltaWindow", clause.take(500)) }
            }
        }
    }
    if (filters) { query << ("%24filter=" + encode(filters.join(" and "))) }

    message.setProperty("S4_TARGET_PATH", afterHost(servicePath + "/" + entitySet, hostEndsWithSlash))
    message.setProperty("S4_QUERY_STRING", withSapClient(query.join("&"), sapClient))

    // First page query
    if (page == 1L) {
        def messageLog = messageLogFactory.getMessageLog(message)
        if (messageLog != null) {
            messageLog.addCustomHeaderProperty("FirstPageQuery",
                java.net.URLDecoder.decode(query.join("&"), "UTF-8").take(1000))
        }
    }
    return message
}
