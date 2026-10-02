package com.keboola.cpi.tests.delivery

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import groovy.json.JsonOutput
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// startRun.groovy: the worker unpacks the envelope from the queue and resets the run state.
class StartRunTest {

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.DELIVERY, "startRun", logs)
    }

    static Map envelope(Map overrides = [:]) {
        return [messageId: "run-1", runStartedAt: "2026-09-30T02:00:00", loadMode: "incremental",
                watermark: "2026-09-29T02:00:00", watermarkNote: "", tableId: "in.c-sap.business_partners",
                servicePath: "/sap/opu/odata/sap/API_BUSINESS_PARTNER", entitySet: "A_BusinessPartner",
                select: "BusinessPartner", filter: "", deltaField: "LastChangeDateTime",
                deltaFieldType: "datetimeoffset", deltaPrecision: "second", deltaOverlapMinutes: "15",
                primaryKey: "BusinessPartner", pageSize: "5000", pagingUnsorted: "false"] + overrides
    }

    Message start(Map overrides = [:]) {
        def m = message(body: JsonOutput.toJson(envelope(overrides)))
        script.processData(m)
        return m
    }

    @Test
    void everyEnvelopeFieldBecomesAProperty() {
        def m = start()
        envelope().each { key, value -> assert m.getProperty("DLV_" + key) == value }
        assert m.getProperty("KEBOOLA_MESSAGE_ID") == "run-1"
        assert m.getProperty("PAGE_NUMBER") == "0"
        assert m.getProperty("ROWS_DELIVERED") == "0"
        assert m.getProperty("NEXT_SKIP") == "0"
        assert m.getProperty("NEXT_LINK") == ""
        assert m.getProperty("MORE_PAGES") == "true"
        assert m.getProperty("RUN_FAILED") == ""
        assert m.getProperty("PAGE_VERDICT") == "ok"
        assert m.getProperty("DELIVERY_NOTES") == null
        def log = logs.logOf(m)
        assert log.customHeaderProperties["KeboolaMessageId"] == "run-1"
        assert log.customHeaderProperties["TableId"] == "in.c-sap.business_partners"
        assert log.customHeaderProperties["LoadMode"] == "incremental"
        assert log.customHeaderProperties["WatermarkRead"] == null
        assert log.customHeaderProperties["PagingUnsorted"] == null
        assert log.properties["Watermark"] == "2026-09-29T02:00:00"
        assert log.properties["RunStartedAt"] == "2026-09-30T02:00:00"
    }

    @Test
    void failedWatermarkReadIsCarriedIntoTheWorker() {
        def m = start([watermark: "", watermarkNote: "read failed: store down"])
        assert m.getProperty("DLV_watermark") == ""
        assert m.getProperty("DELIVERY_NOTES") == "watermark read failed: store down; the full window was read"
        assert logs.logOf(m).customHeaderProperties["WatermarkRead"] == "failed, full window"
        assert logs.logOf(m).properties["Watermark"] == "(none — full read)"
    }

    @Test
    void unsortedPagingIsMarkedOnTheWorker() {
        assert logs.logOf(start([loadMode: "full", primaryKey: "", pagingUnsorted: "true"])).customHeaderProperties["PagingUnsorted"] == "true"
    }

    @Test
    void anUnreadableEnvelopeStillStartsARun() {
        def m = message(body: "not json")
        script.processData(m)
        assert m.getProperty("KEBOOLA_MESSAGE_ID") == "unknown"
        assert m.getProperty("DLV_tableId") == ""
        assert m.getProperty("DLV_deltaOverlapMinutes") == ""
        assert m.getProperty("MORE_PAGES") == "true"
        assert logs.logOf(m).properties["Watermark"] == "(none — full read)"
    }
}
