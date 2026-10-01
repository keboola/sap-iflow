package com.keboola.cpi.tests.delivery

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.asdk.datastore.DataBean
import com.sap.it.api.asdk.datastore.DataConfig
import com.sap.it.api.asdk.datastore.DataStoreService
import com.sap.it.api.asdk.runtime.Factory
import com.sap.it.api.msglog.MessageLogFactory
import groovy.json.JsonSlurper
import org.junit.After
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// commitWatermark.groovy: the end of a run — the outcome the Router reads, the watermark, the summary.
class CommitWatermarkTest {

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.DELIVERY, "commitWatermark", logs)
    }

    @After
    void forgetServices() {
        Factory.reset()
    }

    static Map run(Map overrides = [:]) {
        return [KEBOOLA_MESSAGE_ID: "run-1", DLV_tableId: "in.c-sap.business_partners", DLV_loadMode: "full",
                DLV_runStartedAt: "2026-09-30T02:00:00", PAGE_NUMBER: "4", ROWS_DELIVERED: "315",
                MORE_PAGES: "false", RUN_FAILED: "", RUN_FAILED_DETAIL: ""] + overrides
    }

    Message commit(Map overrides = [:]) {
        def m = message(properties: run(overrides))
        script.processData(m)
        return m
    }

    static Map body(Message m) {
        return new JsonSlurper().parseText(m.getBody().toString())
    }

    // Delivered
    @Test
    void fullLoadEndsDeliveredWithTheSummary() {
        def m = commit()
        assert m.getProperty("RUN_OUTCOME") == "DELIVERED"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "DELIVERED"
        def b = body(m)
        assert b.messageId == "run-1"
        assert b.tableId == "in.c-sap.business_partners"
        assert b.pages == "4"
        assert b.rowsDelivered == "315"
        assert b.loadMode == "full"
        assert b.watermark == "not applicable (full load)"
        assert m.getHeaders()["Content-Type"] == "application/json"
        def log = logs.logOf(m)
        assert log.customHeaderProperties["DeliveryOutcome"] == "DELIVERED"
        assert log.customHeaderProperties["Pages"] == "4"
        assert log.customHeaderProperties["RowsDelivered"] == "315"
        assert log.customHeaderProperties["Watermark"] == "not applicable (full load)"
        assert log.attachments.isEmpty()
    }

    @Test
    void incrementalLoadWritesTheWatermark() {
        def written = [:]
        Factory.register(DataStoreService, [put: { DataBean bean, DataConfig config ->
            written.store = config.storeName; written.id = config.id; written.overwrite = config.overwrite
            written.state = new JsonSlurper().parseText(new String(bean.getDataAsArray(), "UTF-8"))
        }] as DataStoreService)
        def m = commit([DLV_loadMode: "incremental", DELIVERY_NOTES: "table in.c-sap.business_partners: exists"])
        assert written.store == "KeboolaDeliveryState"
        assert written.id == "in.c-sap.business_partners"
        assert written.overwrite == true
        assert written.state.watermark == "2026-09-30T02:00:00"
        assert written.state.messageId == "run-1"
        assert written.state.pages == "4"
        assert written.state.rows == "315"
        assert m.getProperty("RUN_OUTCOME") == "DELIVERED"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "DELIVERED"
        assert body(m).watermark == "2026-09-30T02:00:00"
        assert logs.logOf(m).customHeaderProperties["Watermark"] == "2026-09-30T02:00:00"
        assert logs.logOf(m).properties["DeliveryNotes"] == "table in.c-sap.business_partners: exists"
    }

    // 1.1.0 (M6): a watermark that could not be saved is a status of its own, never silent
    @Test
    void failedWatermarkWriteIsDeliveredWatermarkFailed() {
        Factory.register(DataStoreService, [put: { DataBean bean, DataConfig config ->
            throw new IllegalStateException("store full")
        }] as DataStoreService)
        def m = commit([DLV_loadMode: "incremental"])
        assert m.getProperty("RUN_OUTCOME") == "DELIVERED"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "DELIVERED_WATERMARK_FAILED"
        assert body(m).watermark == "write failed: store full"
        def log = logs.logOf(m)
        assert log.customHeaderProperties["DeliveryOutcome"] == "DELIVERED_WATERMARK_FAILED"
        assert log.customHeaderProperties["Watermark"] == "write failed: store full"
        assert log.customHeaderProperties["RowsDelivered"] == "315"
        assert log.attachments["WatermarkError"].mediaType == "text/plain"
        assert log.attachments["WatermarkError"].content.contains("store full")
        assert log.attachments["WatermarkError"].content.contains("2026-09-30T02:00:00")
        assert log.attachments["WatermarkError"].content.contains("previous watermark")
    }

    @Test
    void missingDataStoreServiceIsAFailedWrite() {
        def m = commit([DLV_loadMode: "incremental"])
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "DELIVERED_WATERMARK_FAILED"
        assert body(m).watermark == "write failed: no data store service"
        assert logs.logOf(m).attachments["WatermarkError"] != null
    }

    // 1.1.0 (M4): a final failure is the outcome the Router sends to the Escalation End Event
    @Test
    void finalFailureEndsFailedWithTheErrorBody() {
        def m = commit([RUN_FAILED: "KEBOOLA_FAILED", RUN_FAILED_DETAIL: "Target table in.c-sap.x creation failed (HTTP 404): no bucket",
                        DELIVERY_NOTES: "table in.c-sap.x: creation failed"])
        assert m.getProperty("RUN_OUTCOME") == "FAILED"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "KEBOOLA_FAILED"
        def b = body(m)
        assert b.error.code == "KEBOOLA_FAILED"
        assert b.error.message == "Target table in.c-sap.x creation failed (HTTP 404): no bucket"
        assert b.error.messageId == "run-1"
        assert b.error.pagesDelivered == 3
        assert b.error.rowsDelivered == 315
        def log = logs.logOf(m)
        assert log.customHeaderProperties["DeliveryOutcome"] == "FAILED"
        assert log.customHeaderProperties["Watermark"] == "not advanced"
        assert log.attachments["ErrorDetails"].content == "Target table in.c-sap.x creation failed (HTTP 404): no bucket"
        assert log.attachments["DeliveryNotes"].content == "table in.c-sap.x: creation failed"
    }

    @Test
    void failedRunNeverWritesTheWatermark() {
        def calls = 0
        Factory.register(DataStoreService, [put: { DataBean bean, DataConfig config -> calls++ }] as DataStoreService)
        def m = commit([DLV_loadMode: "incremental", RUN_FAILED: "UPSTREAM_FAILED", RUN_FAILED_DETAIL: "HTTP 404 from SAP S/4HANA on page 1"])
        assert calls == 0
        assert m.getProperty("RUN_OUTCOME") == "FAILED"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "UPSTREAM_FAILED"
    }

    // The page ceiling: pages left over when the loop stopped is a CONFIG_ERROR, and a final failure
    @Test
    void pagesLeftAtTheCeilingAreAConfigError() {
        def m = commit([MORE_PAGES: "true", PAGE_NUMBER: "99999", ROWS_DELIVERED: "99999000"])
        assert m.getProperty("RUN_OUTCOME") == "FAILED"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "CONFIG_ERROR"
        def b = body(m)
        assert b.error.code == "CONFIG_ERROR"
        assert b.error.message.startsWith("Delivery stopped after 99999 pages with more pages remaining")
        assert b.error.message.contains("Increase PAGE_SIZE or narrow ODATA_FILTER")
        assert b.error.pagesDelivered == 99999
        assert logs.logOf(m).customHeaderProperties["Watermark"] == "not advanced"
    }

    @Test
    void aFailedRunAtTheCeilingKeepsItsOwnCode() {
        def m = commit([MORE_PAGES: "true", RUN_FAILED: "KEBOOLA_FAILED", RUN_FAILED_DETAIL: "import refused"])
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "KEBOOLA_FAILED"
        assert body(m).error.pagesDelivered == 3
    }
}
