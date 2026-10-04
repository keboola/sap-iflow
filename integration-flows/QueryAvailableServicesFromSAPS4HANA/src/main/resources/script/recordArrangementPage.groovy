import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

static int toInt(String v, int dflt) {
    try { return (v != null && v.isInteger()) ? v.toInteger() : dflt } catch (Exception ignored) { return dflt }
}

static long toLong(String v, long dflt) {
    try { return (v != null && v.isLong()) ? v.toLong() : dflt } catch (Exception ignored) { return dflt }
}

static Object parseJson(Object source) {
    if (source instanceof Reader) { return new JsonSlurper().parse((Reader) source) }
    String text = source?.toString()
    if (!text) { throw new IllegalArgumentException("The JSON input text should neither be null nor empty.") }
    return new JsonSlurper().parse(new StringReader(text))
}

static List entitySets() {
    return ["CommunicationArrangements", "CommArrangementsInboundServ", "CommArrangementsInboundUsers"]
}

static String arrangementsBase() {
    return "/sap/opu/odata4/sap/aps_com_api_ca_read/srvd_a2x/sap/communicationarrangement/0001"
}

// One row of a page, reduced to what the list needs: plain strings, no personal data
static Map compact(int set, Map r) {
    String uuid = (r["CommunicationArrangementUUID"] ?: "").toString()
    if (set == 0) {
        return [uuid: uuid, scenario: (r["CommunicationScenarioID"] ?: r["CommunicationScenarioName"] ?: "").toString()]
    }
    if (set == 1) {
        return [uuid: uuid, id: (r["ServiceID"] ?: "").toString(), type: (r["ServiceType"] ?: "").toString(),
                hidden: "true".equalsIgnoreCase((r["IsHidden"] ?: "").toString())]
    }
    return [uuid: uuid, user: (r["UserName"] ?: "").toString(), client: (r["OAuth2ClientID"] ?: "").toString()]
}

// The next page's path and query, pinned to the configured host whatever the link's host is
static Map nextTarget(String link) {
    String pathAndQuery
    if (link ==~ /(?i)^https?:\/\/.*/) {
        pathAndQuery = link.replaceFirst(/(?i)^https?:\/\/[^\/?#]*/, "")
    } else {
        pathAndQuery = arrangementsBase() + "/" + link
    }
    int q = pathAndQuery.indexOf("?")
    return [path: q >= 0 ? pathAndQuery.substring(0, q) : pathAndQuery,
            query: q >= 0 ? pathAndQuery.substring(q + 1) : ""]
}

def Message processData(Message message) {
    def messageLog = messageLogFactory.getMessageLog(message)
    def notes = message.getProperty("CATALOG_NOTES")
    def sets = entitySets()
    def stores = ["ARR_ARRANGEMENTS", "ARR_INBOUND", "ARR_USERS"]
    int set = toInt(message.getProperty("ARR_SET")?.toString(), 0)
    int page = toInt(message.getProperty("ARR_PAGE")?.toString(), 0)
    int status = toInt(message.getHeaders().get("CamelHttpResponseCode")?.toString(), 0)
    long ms = java.time.Instant.now().toEpochMilli() - toLong(message.getProperty("ARR_CALL_T0")?.toString(), 0L)
    boolean first = set == 0 && page == 0
    if (first) { message.setProperty("ARR_FIRST_STATUS", status.toString()) }

    // The answer
    boolean more = false
    if (status == 200) {
        def j = null
        try { j = parseJson(message.getBody(java.io.Reader)) } catch (Exception ignored) { j = null }
        if (!(j instanceof Map) || !(j["value"] instanceof List)) {
            if (first) { message.setProperty("ARR_FIRST_STATUS", "NOT_JSON") }
            message.setProperty("ARR_OUTCOME", "NOT_JSON")
            notes << ("arrangements: " + sets[set] + " answered HTTP 200 but not with JSON")
        } else {
            def rows = message.getProperty(stores[set])
            j["value"].each { r -> if (r instanceof Map) { rows << compact(set, (Map) r) } }
            page++
            notes << ("arrangements: " + sets[set] + " page " + page + " -> " + j["value"].size() + " rows in " + ms + " ms")
            def next = j["@odata.nextLink"]?.toString()
            if (next && page < 50) {
                def target = nextTarget(next)
                message.setProperty("ARR_NEXT_PATH", target.path)
                message.setProperty("ARR_NEXT_QUERY", target.query)
                message.setProperty("ARR_PAGE", page.toString())
                more = true
            } else {
                if (next) { notes << ("arrangements: " + sets[set] + " stopped after 50 pages") }
                if (set + 1 < sets.size()) {
                    message.setProperty("ARR_SET", (set + 1).toString())
                    message.setProperty("ARR_PAGE", "0")
                    message.setProperty("ARR_NEXT_PATH", arrangementsBase() + "/" + sets[set + 1])
                    message.setProperty("ARR_NEXT_QUERY", "")
                    more = true
                } else {
                    message.setProperty("ARR_OUTCOME", "OK")
                }
            }
        }
    } else {
        message.setProperty("ARR_OUTCOME", "HTTP_" + status)
        notes << ("arrangements: " + sets[set] + " answered HTTP " + status + " in " + ms + " ms")
    }

    // Deadline: no further call starts after 35 s, so the whole read ends inside 45 s
    long elapsed = java.time.Instant.now().toEpochMilli() - toLong(message.getProperty("CATALOG_T0")?.toString(), 0L)
    if (more && elapsed > 35000L) {
        message.setProperty("ARR_OUTCOME", "DEADLINE")
        notes << ("arrangements: stopped after " + elapsed + " ms, before " + sets[set])
        more = false
    }
    message.setProperty("ARR_MORE", more ? "true" : "false")
    String outcome = message.getProperty("ARR_OUTCOME")?.toString()
    if (!more && outcome != "OK" && outcome != "DEADLINE"
            && message.getProperty("CATALOG_SOURCE_MODE")?.toString() == "auto") {
        message.setProperty("CATALOG_TRY_GATEWAY", "true")
    }
    if (messageLog != null && !more) {
        messageLog.setStringProperty("ArrangementCalls", message.getProperty("ARR_CALLS")?.toString())
        messageLog.setStringProperty("ArrangementOutcome", message.getProperty("ARR_OUTCOME")?.toString())
    }
    return message
}
