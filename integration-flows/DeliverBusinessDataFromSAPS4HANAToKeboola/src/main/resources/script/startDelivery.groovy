import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.asdk.datastore.DataStoreService
import com.sap.it.api.asdk.runtime.Factory
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.UUID

static String cfg(Message message, String key, String fallback) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return fallback }
    return v
}

static void requireHttps(String value, String name) {
    if (value && !value.toLowerCase().startsWith("https://")) {
        throw new IllegalStateException("CONFIG: " + name + " must start with https:// — " +
            "credentials and business data are never sent over an unencrypted connection.")
    }
}

static void requireOneOf(String value, String name, List allowed) {
    if (!allowed.contains(value)) {
        throw new IllegalStateException("CONFIG: " + name + " must be one of " +
            allowed.join(", ") + "; it is '" + value + "'.")
    }
}

static String servicePathOf(String value) {
    if (!value) {
        throw new IllegalStateException("CONFIG: SERVICE_PATH is not configured on this integration flow.")
    }
    String path = value.replaceAll('^/+|/+$', "")
    if (value.contains("://") || !path) {
        throw new IllegalStateException("CONFIG: SERVICE_PATH is the path of the service without the host, " +
            "for example /sap/opu/odata/sap/API_BUSINESS_PARTNER; it is '" + value + "'.")
    }
    if (value.contains("?") || (value =~ '[\\s\\p{Z}\\p{Cc}\\p{Cf}]').find()) {
        throw new IllegalStateException("CONFIG: SERVICE_PATH must be a path without a question mark and " +
            "without blanks, for example /sap/opu/odata/sap/API_BUSINESS_PARTNER; it is '" + value + "'.")
    }
    return "/" + path
}

static String entitySetOf(String value) {
    String name = (value ?: "").replaceAll('^/+|/+$', "")
    if (!name) {
        throw new IllegalStateException("CONFIG: ENTITY_SET is not configured on this integration flow.")
    }
    if (name.contains("?") || (name =~ '[\\s\\p{Z}\\p{Cc}\\p{Cf}]').find()) {
        throw new IllegalStateException("CONFIG: ENTITY_SET must be the name of one collection, " +
            "for example A_BusinessPartner; it is '" + value + "'.")
    }
    return name
}

def Message processData(Message message) {
    def headers = message.getHeaders()

    // Method check
    def httpMethod = headers.get("CamelHttpMethod")?.toString()?.trim()?.toUpperCase()
    if (httpMethod && httpMethod != "POST") {
        throw new IllegalStateException("METHOD: " + httpMethod)
    }

    String messageId = UUID.randomUUID().toString()

    def tableId = cfg(message, "CFG_KEBOOLA_TABLE_ID", "")
    def loadMode = cfg(message, "CFG_LOAD_MODE", "full").toLowerCase()

    // Settings check
    def s4Host = cfg(message, "CFG_S4_HOSTNAME", "")
    if (!s4Host) { throw new IllegalStateException("CONFIG: S4_HOSTNAME is not configured on this integration flow.") }
    boolean throughCloudConnector = cfg(message, "CFG_PROXY_TYPE", "").toLowerCase() == "sapcc"
    if (throughCloudConnector) {
        String scheme = s4Host.toLowerCase()
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            throw new IllegalStateException("CONFIG: S4_HOSTNAME must start with http:// followed by the " +
                "virtual host defined in the SAP Cloud Connector, for example http://s4hana.virtual:44300.")
        }
    } else {
        requireHttps(s4Host, "S4_HOSTNAME")
    }
    def stackUrl = cfg(message, "CFG_KEBOOLA_STACK_URL", "")
    if (!stackUrl) { throw new IllegalStateException("CONFIG: KEBOOLA_STACK_URL is not configured on this integration flow.") }
    requireHttps(stackUrl, "KEBOOLA_STACK_URL")
    requireHttps(cfg(message, "CFG_KEBOOLA_IMPORTER_URL", ""), "KEBOOLA_IMPORTER_URL")
    if (!tableId) { throw new IllegalStateException("CONFIG: KEBOOLA_TABLE_ID is not configured on this integration flow.") }
    String servicePath = servicePathOf(cfg(message, "CFG_SERVICE_PATH", ""))
    String entitySet = entitySetOf(cfg(message, "CFG_ENTITY_SET", ""))
    requireOneOf(loadMode, "LOAD_MODE", ["full", "incremental"])
    def pageSizeText = cfg(message, "CFG_PAGE_SIZE", "5000")
    long pageSize = -1L
    try { pageSize = Long.parseLong(pageSizeText) } catch (Exception ignored) { }
    if (pageSize < 1L || pageSize > 20000L) {
        throw new IllegalStateException("CONFIG: PAGE_SIZE must be a whole number from 1 to 20000; it is '" + pageSizeText + "'.")
    }
    def timeoutText = cfg(message, "CFG_HTTP_TIMEOUT_MS", "")
    if (timeoutText) {
        long timeoutMs = -1L
        if (timeoutText ==~ '[0-9]{1,9}') { timeoutMs = Long.parseLong(timeoutText) }
        if (timeoutMs < 1000L || timeoutMs > 3600000L) {
            throw new IllegalStateException("CONFIG: HTTP_TIMEOUT_MS must be a whole number of milliseconds " +
                "from 1000 to 3600000; it is '" + timeoutText + "'.")
        }
    }
    def namesOf = { String text ->
        text.split(",").collect { it.replaceAll('^[\\s\\p{Z}\\p{Cc}\\p{Cf}]+|[\\s\\p{Z}\\p{Cc}\\p{Cf}]+$', "") }
            .findAll { it }.unique()
    }
    def deltaFieldText = cfg(message, "CFG_DELTA_FIELD", "")
    def deltaFields = namesOf(deltaFieldText)
    def deltaFieldType = cfg(message, "CFG_DELTA_FIELD_TYPE", "datetime").toLowerCase()
    def deltaPrecision = cfg(message, "CFG_DELTA_PRECISION", "day").toLowerCase()
    def overlapText = cfg(message, "CFG_DELTA_OVERLAP_MINUTES", "15")
    long overlapMinutes = -1L
    if (overlapText ==~ '[0-9]{1,5}') { overlapMinutes = Long.parseLong(overlapText) }
    def primaryKey = cfg(message, "CFG_PRIMARY_KEY", "")
    def selected = namesOf(cfg(message, "CFG_ODATA_SELECT", ""))
    if (selected.contains("*")) { selected = [] }
    def keyColumns = namesOf(primaryKey)
    def notSelected = keyColumns.findAll { !selected.contains(it) }
    if (selected && notSelected) {
        throw new IllegalStateException("CONFIG: PRIMARY_KEY names " + notSelected.join(", ") +
            ", which ODATA_SELECT does not list. Add " + (notSelected.size() > 1 ? "them" : "it") +
            " to ODATA_SELECT, or leave ODATA_SELECT empty to read every field.")
    }
    if (loadMode == "incremental") {
        requireOneOf(deltaFieldType, "DELTA_FIELD_TYPE", ["datetime", "datetimeoffset", "date"])
        requireOneOf(deltaPrecision, "DELTA_PRECISION", ["day", "second"])
        if (!deltaFields) { throw new IllegalStateException("CONFIG: incremental loading needs DELTA_FIELD, the field that says when a record changed.") }
        if (deltaFields.any { !(it ==~ '[A-Za-z_][A-Za-z0-9_]*') }) {
            throw new IllegalStateException("CONFIG: DELTA_FIELD must be one field name, or several separated " +
                "by commas; it is '" + deltaFieldText + "'.")
        }
        if (!keyColumns) { throw new IllegalStateException("CONFIG: incremental loading needs PRIMARY_KEY, so that records read again are updated rather than added twice.") }
        if (overlapMinutes < 0L || overlapMinutes > 10080L) {
            throw new IllegalStateException("CONFIG: DELTA_OVERLAP_MINUTES must be a whole number of minutes " +
                "from 0 to 10080; it is '" + overlapText + "'.")
        }
    }
    // Unsorted paging
    boolean pagingUnsorted = loadMode == "full" && !keyColumns

    // Run start
    def runStartedAt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .format(java.time.Instant.now().atZone(java.time.ZoneOffset.UTC))

    // Watermark
    String watermark = ""
    String watermarkNote = ""
    if (loadMode == "incremental") {
        try {
            def dataStore = new Factory(DataStoreService.class).getService()
            if (dataStore == null) {
                watermarkNote = "read failed: no data store service"
            } else {
                def bean = dataStore.get("KeboolaDeliveryState", tableId)
                if (bean != null) {
                    def state = new JsonSlurper().parse(
                        new InputStreamReader(new ByteArrayInputStream(bean.getDataAsArray()), "UTF-8"))
                    watermark = state["watermark"]?.toString() ?: ""
                }
            }
        } catch (Exception e) {
            watermark = ""
            watermarkNote = "read failed: " + (e.getMessage() ?: e.getClass().getSimpleName())
                .replaceAll('[\\s\\p{Cc}]+', " ").trim().take(200)
        }
    }

    // Envelope
    def envelope = [
        messageId:     messageId,
        runStartedAt:  runStartedAt,
        loadMode:      loadMode,
        watermark:     watermark,
        watermarkNote: watermarkNote,
        tableId:       tableId,
        servicePath:   servicePath,
        entitySet:     entitySet,
        select:        selected.join(","),
        filter:        cfg(message, "CFG_ODATA_FILTER", ""),
        deltaField:    deltaFields.join(","),
        deltaFieldType: deltaFieldType,
        deltaPrecision: deltaPrecision,
        deltaOverlapMinutes: Long.toString(Math.max(0L, overlapMinutes)),
        primaryKey:    keyColumns.join(","),
        pageSize:      Long.toString(pageSize),
        pagingUnsorted: pagingUnsorted ? "true" : "false",
    ]

    message.setProperty("KEBOOLA_MESSAGE_ID", messageId)
    message.setBody(JsonOutput.toJson(envelope))
    message.setHeader("Content-Type", "application/json")
    message.setHeader("CamelHttpResponseCode", 202)

    message.setProperty("SAP_MessageProcessingLogCustomStatus", "QUEUED")
    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("KeboolaMessageId", messageId)
        messageLog.addCustomHeaderProperty("TableId", tableId)
        messageLog.addCustomHeaderProperty("LoadMode", loadMode)
        messageLog.setStringProperty("Watermark", watermark ?: "(none — full read)")
        messageLog.setStringProperty("RunStartedAt", runStartedAt)
        if (watermarkNote) {
            messageLog.addCustomHeaderProperty("WatermarkRead", "failed, full window")
            messageLog.setStringProperty("WatermarkReadError", watermarkNote)
        }
        if (pagingUnsorted) { messageLog.addCustomHeaderProperty("PagingUnsorted", "true") }
    }
    return message
}
