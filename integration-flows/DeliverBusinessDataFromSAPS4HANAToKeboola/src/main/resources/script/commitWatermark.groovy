import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.asdk.datastore.DataBean
import com.sap.it.api.asdk.datastore.DataConfig
import com.sap.it.api.asdk.datastore.DataStoreService
import com.sap.it.api.asdk.runtime.Factory
import groovy.json.JsonOutput

static String jsonEscape(String s) {
    if (s == null) { return "" }
    return s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
            .replace('\r', '\\r').replace('\t', '\\t')
            .replaceAll('[\\x00-\\x1F]', ' ')
}

static long count(String text) {
    try { return Long.parseLong(text) } catch (Exception ignored) { return 0L }
}

def Message processData(Message message) {
    def messageLog = messageLogFactory.getMessageLog(message)
    def messageId = message.getProperty("KEBOOLA_MESSAGE_ID")?.toString() ?: "unknown"
    def tableId = message.getProperty("DLV_tableId")?.toString() ?: ""
    def loadMode = message.getProperty("DLV_loadMode")?.toString() ?: "full"
    def runStartedAt = message.getProperty("DLV_runStartedAt")?.toString() ?: ""
    def pages = message.getProperty("PAGE_NUMBER")?.toString() ?: "0"
    def rows = message.getProperty("ROWS_DELIVERED")?.toString() ?: "0"
    def notes = message.getProperty("DELIVERY_NOTES")?.toString() ?: ""

    // Page ceiling
    boolean stoppedAtCeiling = !(message.getProperty("RUN_FAILED")?.toString()) &&
        "true".equalsIgnoreCase(message.getProperty("MORE_PAGES")?.toString() ?: "false")
    if (stoppedAtCeiling) {
        message.setProperty("RUN_FAILED", "CONFIG_ERROR")
        message.setProperty("RUN_FAILED_DETAIL", "Delivery stopped after " + pages + " pages with more " +
            "pages remaining: the page loop reached its ceiling. Increase PAGE_SIZE or narrow " +
            "ODATA_FILTER. The watermark was not advanced and " + rows + " rows were delivered.")
    }

    // Final failure
    def runFailed = message.getProperty("RUN_FAILED")?.toString() ?: ""
    if (runFailed) {
        def detail = message.getProperty("RUN_FAILED_DETAIL")?.toString() ?: "no detail recorded"
        long pagesDelivered = stoppedAtCeiling ? count(pages) : Math.max(0L, count(pages) - 1L)
        message.setProperty("SAP_MessageProcessingLogCustomStatus", runFailed)
        def timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                .format(java.time.Instant.now().atZone(java.time.ZoneId.systemDefault()))
        message.setBody("""{
  "error": {
    "code": "${runFailed}",
    "message": "${jsonEscape(detail)}",
    "messageId": "${jsonEscape(messageId)}",
    "pagesDelivered": ${pagesDelivered},
    "rowsDelivered": ${count(rows)},
    "timestamp": "${timestamp}"
  }
}""")
        message.setHeader("Content-Type", "application/json")
        if (messageLog != null) {
            messageLog.addCustomHeaderProperty("KeboolaMessageId", messageId)
            messageLog.addCustomHeaderProperty("DeliveryOutcome", "FAILED")
            messageLog.addCustomHeaderProperty("Pages", pages)
            messageLog.addCustomHeaderProperty("RowsDelivered", rows)
            messageLog.addCustomHeaderProperty("TableId", tableId)
            messageLog.addCustomHeaderProperty("Watermark", "not advanced")
            messageLog.addAttachmentAsString("ErrorDetails", detail, "text/plain")
            if (notes) { messageLog.addAttachmentAsString("DeliveryNotes", notes, "text/plain") }
        }
        return message
    }

    // Watermark
    String committed = "not applicable (full load)"
    if (loadMode == "incremental" && tableId && runStartedAt) {
        try {
            def dataStore = new Factory(DataStoreService.class).getService()
            if (dataStore != null) {
                def bean = new DataBean()
                bean.setDataAsArray(JsonOutput.toJson([
                    watermark: runStartedAt, messageId: messageId,
                    pages: pages, rows: rows,
                ]).getBytes("UTF-8"))
                def config = new DataConfig()
                config.setStoreName("KeboolaDeliveryState")
                config.setId(tableId)
                config.setOverwrite(true)
                dataStore.put(bean, config)
                committed = runStartedAt
            } else {
                committed = "failed: no data store service"
            }
        } catch (Exception e) {
            committed = "failed: " + (e.getMessage() ?: e.getClass().getSimpleName())
        }
    }

    // Delivery summary
    message.setProperty("SAP_MessageProcessingLogCustomStatus", "DELIVERED")
    message.setBody(JsonOutput.toJson([
        messageId: messageId, tableId: tableId, pages: pages,
        rowsDelivered: rows, loadMode: loadMode, watermark: committed,
    ]))
    message.setHeader("Content-Type", "application/json")

    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("KeboolaMessageId", messageId)
        messageLog.addCustomHeaderProperty("DeliveryOutcome", "DELIVERED")
        messageLog.addCustomHeaderProperty("Pages", pages)
        messageLog.addCustomHeaderProperty("RowsDelivered", rows)
        messageLog.addCustomHeaderProperty("TableId", tableId)
        messageLog.addCustomHeaderProperty("Watermark", committed)
        if (notes) { messageLog.setStringProperty("DeliveryNotes", notes) }
    }
    return message
}
