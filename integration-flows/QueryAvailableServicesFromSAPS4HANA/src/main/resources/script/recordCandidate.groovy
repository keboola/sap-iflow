import com.sap.gateway.ip.core.customdev.util.Message

static int toInt(String v, int dflt) {
    try { return (v != null && v.isInteger()) ? v.toInteger() : dflt } catch (Exception ignored) { return dflt }
}

static long toLong(String v, long dflt) {
    try { return (v != null && v.isLong()) ? v.toLong() : dflt } catch (Exception ignored) { return dflt }
}

def Message processData(Message message) {
    def notes = message.getProperty("CATALOG_NOTES")
    if (!(notes instanceof List)) { notes = []; message.setProperty("CATALOG_NOTES", notes) }
    def queue = message.getProperty("VERIFY_QUEUE")
    if (!(queue instanceof List)) { queue = [] }
    def resolved = message.getProperty("VERIFY_RESOLVED")
    if (!(resolved instanceof Map)) { resolved = [:] }
    int index = toInt(message.getProperty("VERIFY_INDEX")?.toString(), 0)
    int calls = toInt(message.getProperty("VERIFY_CALLS")?.toString(), 0) + 1
    int limit = toInt(message.getProperty("CATALOG_VERIFY_LIMIT_N")?.toString(), 20)
    def candidate = index < queue.size() ? queue[index] : [name: "", path: ""]

    // The answer: a service document is JSON with HTTP 200; anything else is not this service
    int status = toInt(message.getHeaders().get("CamelHttpResponseCode")?.toString(), 0)
    long ms = java.time.Instant.now().toEpochMilli() - toLong(message.getProperty("VERIFY_CALL_T0")?.toString(), 0L)
    String head = (message.getBody(String) ?: "").trim()
    boolean found = status == 200 && head.startsWith("{")
    notes << ("verify " + candidate.name + ": " + candidate.path + "/ -> HTTP " + status + (found ? " (listed)" : "") + " in " + ms + " ms")

    int next = index + 1
    if (found) {
        resolved[candidate.name] = candidate.path
        while (next < queue.size() && queue[next].name == candidate.name) { next++ }
    }

    // Bounded: the configured number of calls, and no call starts after 35 s
    long elapsed = java.time.Instant.now().toEpochMilli() - toLong(message.getProperty("CATALOG_T0")?.toString(), 0L)
    boolean more = next < queue.size() && calls < limit && elapsed < 35000L
    if (!more && next < queue.size()) {
        notes << ("verify: stopped after " + calls + " calls, " + elapsed + " ms")
    }
    message.setProperty("VERIFY_INDEX", next.toString())
    message.setProperty("VERIFY_CALLS", calls.toString())
    message.setProperty("VERIFY_RESOLVED", resolved)
    message.setProperty("VERIFY_MORE", more ? "true" : "false")
    message.setBody("")
    return message
}
