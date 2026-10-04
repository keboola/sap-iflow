import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

static int toInt(String v, int dflt) {
    try { return (v != null && v.isInteger()) ? v.toInteger() : dflt } catch (Exception ignored) { return dflt }
}

static long toLong(String v, long dflt) {
    try { return (v != null && v.isLong()) ? v.toLong() : dflt } catch (Exception ignored) { return dflt }
}

// Views
static String technicalName(String s) {
    if (s == null) { return "" }
    return s.toUpperCase()
            .replaceFirst(/^\/[^\/]+\//, "")
            .replaceFirst(/^[ZY](?=(API|UI|C)_)/, "")
            .replaceFirst(/_\d{4}$/, "")
}

static boolean isUiRow(Map r) {
    for (k in ["TechnicalServiceName", "Title", "ID"]) {
        def v = r[k]?.toString()
        if (v && technicalName(v).startsWith("UI_")) { return true }
    }
    return false
}

static boolean customerNamespace(String id) {
    def s = (id ?: "").toUpperCase()
    return s.startsWith("Z") || s.startsWith("Y")
}

static boolean inView(Map r, String view) {
    if (view == "all") { return true }
    if (isUiRow(r)) { return false }
    def name = technicalName((r["Title"] ?: r["ID"] ?: "").toString())
    def url = (r["ServiceUrl"] ?: "").toString()
    boolean v4 = url.contains("/sap/opu/odata4/")
    if (r.containsKey("ServiceType")) {
        def type = (r["ServiceType"] ?: "").toString().toUpperCase()
        if (type == "UI") { return false }
        if (view == "extended") { return true }
        boolean customerBuilt = (r["IsSapService"]?.toString() ?: "").equalsIgnoreCase("false")
        return type == "WEB_API" || customerBuilt || name.startsWith("API_")
    }
    if (v4 && !url.contains("/srvd_a2x/")) {
        return customerNamespace((r["ID"] ?: "").toString()) && !name.startsWith("I_")
    }
    return true
}

static String odataVersionOf(Map r) {
    def u = (r["ServiceUrl"] ?: r["MetadataUrl"] ?: "").toString().toLowerCase()
    if (u.contains("/sap/opu/odata4/")) { return "v4" }
    if (u.contains("/sap/opu/odata/")) { return "v2" }
    return ""
}

// The outbound filter: view, OData version, the description's markers, API_ first then alphabetical
static Map filterRows(List rows, String view, String odata) {
    int hidden = 0
    int hiddenVersion = 0
    def kept = rows.findAll { r ->
        boolean keep = inView(r, view)
        if (keep && odata != "both" && odataVersionOf(r) != odata) { keep = false; hiddenVersion++ }
        if (!keep) { hidden++ }
        return keep
    }
    kept.each { r ->
        def d = (r["Description"] ?: "").toString()
        def ver = odataVersionOf(r)
        if (ver && !d.contains("(OData V")) {
            d = (d ? d + " " : "") + "(OData " + ver.toUpperCase() + ")"
        }
        if ("DEPRECATED".equalsIgnoreCase((r["ReleaseStatus"] ?: "").toString()) && !d.contains("(deprecated)")) {
            d = (d ? d + " " : "") + "(deprecated)"
        }
        r["Description"] = d
    }
    kept = kept.sort { a, b ->
        def ia = technicalName((a["Title"] ?: a["ID"] ?: "").toString())
        def ib = technicalName((b["Title"] ?: b["ID"] ?: "").toString())
        int ra = ia.startsWith("API_") ? 0 : 1
        int rb = ib.startsWith("API_") ? 0 : 1
        return (ra != rb) ? (ra <=> rb) : (ia <=> ib)
    }
    return [rows: kept, hidden: hidden, hiddenVersion: hiddenVersion]
}

def Message processData(Message message) {
    def messageLog = messageLogFactory.getMessageLog(message)
    def messageId = message.getProperty("CATALOG_RUN_ID")?.toString()
        ?: message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: "unknown"
    def notes = message.getProperty("CATALOG_NOTES")
    if (!(notes instanceof List)) { notes = [] }
    String host = message.getProperty("CATALOG_HOST")?.toString() ?: ""
    String source = message.getProperty("CATALOG_SOURCE_USED")?.toString() ?: "unknown"
    String view = message.getProperty("CATALOG_VIEW_EFFECTIVE")?.toString() ?: "interfaces"
    String odata = message.getProperty("CATALOG_ODATA")?.toString() ?: "both"
    def rows = message.getProperty("CATALOG_ROWS")
    if (!(rows instanceof List)) { rows = [] }
    def queue = message.getProperty("VERIFY_QUEUE")
    if (!(queue instanceof List)) { queue = [] }
    def resolved = message.getProperty("VERIFY_RESOLVED")
    if (!(resolved instanceof Map)) { resolved = [:] }
    def unlisted = []
    if (message.getProperty("CATALOG_UNLISTED") instanceof List) { unlisted.addAll(message.getProperty("CATALOG_UNLISTED")) }

    // Verified guesses join the list; the rest is named, not listed
    def seen = [] as Set
    queue.each { c ->
        if (!seen.add(c.name)) { return }
        if (resolved[c.name]) {
            def path = resolved[c.name].toString()
            rows << [ID: c.name, Title: c.name, Description: c.title, ServiceUrl: host + path, MetadataUrl: host + path + "/\$metadata"]
        } else {
            unlisted << c.name
        }
    }
    unlisted = unlisted.unique().sort()

    def filtered = filterRows(rows, view, odata)
    int count = filtered.rows.size()
    if (filtered.hidden > 0) {
        notes << ("view " + view + ": hid " + filtered.hidden + " of " + rows.size() +
                  " service(s) (UI apps, raw CDS views, technical services) - ?include=extended or ?include=all widens the list")
    }
    if (filtered.hiddenVersion > 0) {
        notes << ("odata " + odata + ": hid " + filtered.hiddenVersion + " service(s) speaking the other OData version")
    }
    if (unlisted) {
        notes << ("unlisted: " + unlisted.size() + " OData V4 service(s) without a known address - add them to the value mapping KeboolaServiceAddresses: " +
                  unlisted.join(", "))
    }

    // The answer
    def answer = [results: filtered.rows]
    if ("true".equals(message.getProperty("CATALOG_DEBUG")?.toString())) {
        answer["diagnostics"] = [messageId: messageId, source: source, view: view, unlisted: unlisted,
                                 verification: [calls: toInt(message.getProperty("VERIFY_CALLS")?.toString(), 0),
                                                resolved: resolved.size()],
                                 stats: message.getProperty("CATALOG_STATS") ?: [:], notes: notes]
    }
    def airHeaderName = readCfg(message, "CFG_AIR_HEADER_NAME")
    if (airHeaderName) { message.setHeader(airHeaderName, null) }
    ["Content-Length", "Content-Encoding", "Content-Language", "ETag", "Last-Modified", "Cache-Control",
     "DataServiceVersion", "OData-Version", "sap-message", "sap-messagescount", "Location", "Retry-After",
     "CamelHttpResponseText"].each { message.setHeader(it, null) }
    message.setBody(JsonOutput.toJson([d: answer]))
    message.setHeader("CamelHttpResponseCode", 200)
    message.setHeader("Content-Type", "application/json")
    message.setHeader("X-Keboola-Catalog-Source", source)
    message.setHeader("X-Keboola-Catalog-View", view + ";shown=" + count + ";hidden=" + filtered.hidden +
                                                (odata != "both" ? ";odata=" + odata : ""))
    message.setHeader("X-Keboola-Catalog-Unlisted", unlisted.size().toString())

    // The monitor
    String status = count == 0 ? "CATALOG_EMPTY" : "OK"
    message.setProperty("SAP_MessageProcessingLogCustomStatus", status)
    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("CatalogSource", source)
        messageLog.addCustomHeaderProperty("CatalogCount", count.toString())
        messageLog.addCustomHeaderProperty("CatalogHidden", filtered.hidden.toString())
        messageLog.addCustomHeaderProperty("CatalogView", view)
        messageLog.addCustomHeaderProperty("CatalogUnlisted", unlisted.size().toString())
        messageLog.addCustomHeaderProperty("UpstreamOutcome", "OK")
        messageLog.setStringProperty("CatalogSource", source)
        // The names twice: the searchable custom header (bounded) and the full log property
        String names = unlisted ? unlisted.join(", ") : "none"
        if (unlisted) {
            messageLog.addCustomHeaderProperty("CatalogUnlistedNames", names.length() > 480 ? names.substring(0, 477) + "..." : names)
        }
        messageLog.setStringProperty("CatalogUnlisted", names)
        messageLog.setStringProperty("VerifyCalls", message.getProperty("VERIFY_CALLS")?.toString() ?: "0")
        messageLog.setStringProperty("DurationMs",
            (java.time.Instant.now().toEpochMilli() - toLong(message.getProperty("CATALOG_T0")?.toString(), 0L)).toString())
        if (notes) {
            String joined = notes.join(" | ")
            messageLog.setStringProperty("CatalogDiagnostics",
                joined.length() > 2000 ? joined.substring(0, 2000) + " ..." : joined)
        }
    }
    return message
}
