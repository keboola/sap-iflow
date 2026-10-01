import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

def Message processData(Message message) {
    // Envelope
    def envelope = [:]
    try { envelope = new JsonSlurper().parse(message.getBody(java.io.Reader)) }
    catch (Exception ignored) { envelope = [:] }
    if (!(envelope instanceof Map)) { envelope = [:] }
    ["messageId", "runStartedAt", "loadMode", "watermark", "watermarkNote", "tableId", "servicePath",
     "entitySet", "select", "filter", "deltaField", "deltaFieldType", "deltaPrecision",
     "deltaOverlapMinutes", "primaryKey", "pageSize", "pagingUnsorted"].each { key ->
        message.setProperty("DLV_" + key, envelope[key]?.toString() ?: "")
    }
    def messageId = envelope["messageId"]?.toString() ?: "unknown"
    message.setProperty("KEBOOLA_MESSAGE_ID", messageId)

    // Run state
    message.setProperty("PAGE_NUMBER", "0")
    message.setProperty("ROWS_DELIVERED", "0")
    message.setProperty("NEXT_SKIP", "0")
    message.setProperty("NEXT_LINK", "")
    message.setProperty("MORE_PAGES", "true")
    message.setProperty("RUN_FAILED", "")
    message.setProperty("RUN_FAILED_DETAIL", "")
    message.setProperty("PAGE_VERDICT", "ok")

    // Watermark note
    def watermarkNote = envelope["watermarkNote"]?.toString() ?: ""
    if (watermarkNote) {
        message.setProperty("DELIVERY_NOTES", "watermark " + watermarkNote + "; the full window was read")
    }

    def messageLog = messageLogFactory.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("KeboolaMessageId", messageId)
        messageLog.addCustomHeaderProperty("TableId", envelope["tableId"]?.toString() ?: "")
        messageLog.addCustomHeaderProperty("LoadMode", envelope["loadMode"]?.toString() ?: "")
        messageLog.setStringProperty("Watermark", envelope["watermark"]?.toString() ?: "(none — full read)")
        messageLog.setStringProperty("RunStartedAt", envelope["runStartedAt"]?.toString() ?: "")
        if (watermarkNote) { messageLog.addCustomHeaderProperty("WatermarkRead", "failed, full window") }
        if (envelope["pagingUnsorted"]?.toString() == "true") {
            messageLog.addCustomHeaderProperty("PagingUnsorted", "true")
        }
    }
    return message
}
