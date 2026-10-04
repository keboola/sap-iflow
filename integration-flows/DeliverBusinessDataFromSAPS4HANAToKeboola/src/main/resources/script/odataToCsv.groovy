import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

static String csvEscape(String value) {
    if (value == null) { return "" }
    if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
    return value
}

// SAP's /Date(ms)/ carries no time zone, so the text carries none either (1.1.0).
static String normaliseDate(String value) {
    def matcher = (value =~ /^\/Date\((-?\d+)([+-]\d+)?\)\/$/)
    if (!matcher.matches()) { return value }
    try {
        def format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        format.setTimeZone(TimeZone.getTimeZone("UTC"))
        return format.format(new Date(Long.parseLong(matcher.group(1))))
    } catch (Exception ignored) {
        return value
    }
}

static String normaliseTime(String value) {
    def matcher = (value =~ /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(\.\d+)?S)?$/)
    if (!matcher.matches() || value == "PT") { return value }
    def part = { String s -> s ? s.toInteger() : 0 }
    return String.format("%02d:%02d:%02d", part(matcher.group(1)), part(matcher.group(2)),
                         part(matcher.group(3))) + (matcher.group(4) ?: "")
}

// Keboola refuses a column name over 64 characters; a flattened name can reach that (1.1.0).
static String longColumnOf(List names) {
    return names.find { it.length() > 64 }
}

static String render(Object value) {
    if (value == null) { return "" }
    if (value instanceof BigDecimal) { return ((BigDecimal) value).toPlainString() }
    return normaliseTime(normaliseDate(value.toString()))
}

static void flatten(Map source, String prefix, Map target) {
    source.each { key, value ->
        def name = key.toString()
        if (name == "__metadata" || name.contains("@")) { return }
        if (value instanceof Map) {
            if (value.containsKey("__deferred")) { return }
            flatten((Map) value, prefix + name + "_", target)
            return
        }
        if (value instanceof List) { return }
        target[prefix + name] = render(value)
    }
}

static String startOf(BufferedReader reader, int length) {
    char[] buffer = new char[length]
    int filled = 0
    reader.mark(length + 1)
    while (filled < length) {
        int count = reader.read(buffer, filled, length - filled)
        if (count <= 0) { break }
        filled += count
    }
    reader.reset()
    return new String(buffer, 0, filled)
}

static String identityOf(Object row, List keyNames = null) {
    if (!(row instanceof Map)) { return "" }
    def metadata = row["__metadata"]
    def uri = (metadata instanceof Map) ? metadata["uri"]?.toString() : null
    String address = uri ?: (row["@odata.id"]?.toString() ?: "")
    if (address || !keyNames) { return address }
    def values = keyNames.collect { row[it] == null ? null : row[it].toString() }
    if (values.any { it == null }) { return "" }
    return "key:" + values.collect { it.length() + ":" + it }.join(",")
}

static String pageIdentity(List rows, List keyNames = null) {
    if (!rows) { return "" }
    String first = identityOf(rows[0], keyNames)
    String last = identityOf(rows[rows.size() - 1], keyNames)
    if (!first || !last) { return "" }
    return first + "|" + last + "|" + rows.size()
}

static Message endRun(Message message, String detail, String code = "UPSTREAM_FAILED") {
    message.setProperty("RUN_FAILED", code)
    message.setProperty("RUN_FAILED_DETAIL", detail)
    message.setProperty("MORE_PAGES", "false")
    message.setProperty("ROWS_IN_PAGE", "0")
    message.setProperty("NEXT_LINK", "")
    message.setBody("")
    return message
}

def Message processData(Message message) {
    def page = message.getProperty("PAGE_NUMBER")?.toString() ?: "?"

    // JSON parsing
    def source = message.getBody(java.io.Reader)
    if (source == null) {
        return endRun(message, "SAP S/4HANA answered page " + page + " without a body instead of " +
            "an OData collection.")
    }
    def reader = new BufferedReader(source)
    String start = startOf(reader, 1000)
    if (!start.trim() && start.length() < 1000) {
        return endRun(message, "SAP S/4HANA answered page " + page + " without a body instead of " +
            "an OData collection.")
    }
    def parsed = new JsonSlurper().parse(reader)

    // Row extraction
    def rows = null
    String nextLink = ""
    if (parsed instanceof Map && parsed.containsKey("d")) {
        def d = parsed["d"]
        if (d instanceof Map && d.containsKey("results")) {
            if (d["results"] instanceof List) {
                rows = (List) d["results"]
                nextLink = d["__next"]?.toString() ?: ""
            }
        } else if (d instanceof Map) {
            rows = [d]
        }
    } else if (parsed instanceof Map && parsed["value"] instanceof List) {
        rows = (List) parsed["value"]
        nextLink = parsed["@odata.nextLink"]?.toString() ?: ""
    }

    if (rows == null) {
        return endRun(message, "SAP S/4HANA answered page " + page + " with JSON that is not an OData " +
            "collection: " + start.replaceAll('\\s+', " ").trim().take(200))
    }

    // Repeat detection
    long pageSize = 1000L
    try { pageSize = Long.parseLong(message.getProperty("DLV_pageSize")?.toString() ?: "1000") }
    catch (Exception ignored) { }

    boolean askedByLink = (message.getProperty("PAGE_ASKED_BY_LINK")?.toString() ?: "false") == "true"
    if (!askedByLink) {
        def keyNames = (message.getProperty("DLV_primaryKey")?.toString() ?: "")
            .split(",").collect { it.trim() }.findAll { it }
        String identity = pageIdentity(rows, keyNames)
        String previousIdentity = message.getProperty("SKIP_PAGE_IDENTITY")?.toString() ?: ""
        message.setProperty("SKIP_PAGE_IDENTITY", identity)
        if (identity && identity == previousIdentity) {
            long previousWindow = 0L
            try { previousWindow = Long.parseLong(message.getProperty("WINDOW_ROWS")?.toString() ?: "0") }
            catch (Exception ignored) { }
            if (previousWindow > pageSize) {
                message.setProperty("PAGE_NOTE", "the records of the first request again, not delivered: " +
                    "SAP S/4HANA had already handed out every record")
                message.setProperty("MORE_PAGES", "false")
                message.setProperty("ROWS_IN_PAGE", "0")
                message.setProperty("NEXT_LINK", "")
                message.setBody("")
                return message
            }
            if (identity.startsWith("key:")) {
                return endRun(message, 'SAP S/4HANA returned records with the same PRIMARY_KEY values for ' +
                    'two requests in a row: either the service does not apply $skip, or PRIMARY_KEY does ' +
                    'not identify a record. Check PRIMARY_KEY; if it is right, set PAGE_SIZE above the ' +
                    'number of records, or read a service that supports paging.')
            }
            return endRun(message, 'SAP S/4HANA returned the same records for two requests in a row: the ' +
                'service does not apply $skip. Set PAGE_SIZE above the number of records, or read a ' +
                'service that supports paging.')
        }
    }

    // First data page
    if (rows && !(message.getProperty("FIRST_DATA_PAGE")?.toString())) {
        message.setProperty("FIRST_DATA_PAGE", page)
    }

    def flat = rows.collect { row ->
        def target = [:]
        if (row instanceof Map) { flatten((Map) row, "", target) }
        return target
    }

    // Column order
    def columns = message.getProperty("CSV_COLUMNS")?.toString()
    if (!columns) {
        def select = message.getProperty("DLV_select")?.toString() ?: ""
        def names = select ? select.split(",").collect { it.trim() }.findAll { it } : []
        if (!names && flat) { names = new ArrayList(flat[0].keySet()) }
        columns = names.join(",")
        message.setProperty("CSV_COLUMNS", columns)
    }
    def names = columns ? columns.split(",").collect { it.trim() }.findAll { it } : []

    // Column length
    def longColumn = longColumnOf(names)
    if (longColumn) {
        return endRun(message, "Column '" + longColumn + "' is " + longColumn.length() + " characters long; " +
            "Keboola allows 64. Nothing was imported. Leave the field out with ODATA_SELECT, or read a " +
            "service whose field names are shorter.", "KEBOOLA_FAILED")
    }

    // CSV output
    def out = new StringBuilder()
    out.append(names.collect { csvEscape(it) }.join(",")).append("\n")
    flat.each { row ->
        out.append(names.collect { csvEscape(row[it]?.toString()) }.join(",")).append("\n")
    }

    long skip = 0L
    try { skip = Long.parseLong(message.getProperty("NEXT_SKIP")?.toString() ?: "0") }
    catch (Exception ignored) { }

    // Request window
    long windowRows = 0L
    if (askedByLink) {
        try { windowRows = Long.parseLong(message.getProperty("WINDOW_ROWS")?.toString() ?: "0") }
        catch (Exception ignored) { }
    }
    windowRows += rows.size()

    // Paging state
    message.setProperty("ROWS_IN_PAGE", Integer.toString(rows.size()))
    message.setProperty("NEXT_LINK", nextLink)
    message.setProperty("NEXT_SKIP", Long.toString(skip + rows.size()))
    message.setProperty("WINDOW_ROWS", Long.toString(windowRows))
    boolean more
    if (nextLink) {
        more = true
    } else if (askedByLink) {
        more = windowRows >= pageSize
    } else {
        more = rows.size() >= pageSize && rows.size() > 0
    }
    message.setProperty("MORE_PAGES", more ? "true" : "false")
    message.setBody(out.toString())
    message.setHeader("Content-Type", "text/csv")
    return message
}
