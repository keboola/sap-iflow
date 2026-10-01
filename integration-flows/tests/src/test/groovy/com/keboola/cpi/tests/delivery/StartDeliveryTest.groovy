package com.keboola.cpi.tests.delivery

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.asdk.datastore.DataBean
import com.sap.it.api.asdk.datastore.DataStoreService
import com.sap.it.api.asdk.runtime.Factory
import com.sap.it.api.msglog.MessageLogFactory
import groovy.json.JsonSlurper
import org.junit.After
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// startDelivery.groovy: the start checks of a run and the envelope that goes on the queue.
class StartDeliveryTest {

    // 1.1.0 limits (M7): 5000 a page, 20000 at most
    static final String PAGE_SIZE_DEFAULT = "5000"
    static final long PAGE_SIZE_CEILING = 20000L

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.DELIVERY, "startDelivery", logs)
    }

    @After
    void forgetServices() {
        Factory.reset()
    }

    static Map valid() {
        return [CFG_S4_HOSTNAME: "https://s4.example.com",
                CFG_KEBOOLA_STACK_URL: "https://connection.example.com",
                CFG_KEBOOLA_IMPORTER_URL: "https://import.example.com/write-table",
                CFG_KEBOOLA_TABLE_ID: "in.c-sap.business_partners",
                CFG_SERVICE_PATH: "/sap/opu/odata/sap/API_BUSINESS_PARTNER",
                CFG_ENTITY_SET: "A_BusinessPartner"]
    }

    Message start(Map overrides = [:], Map headers = [:]) {
        def m = message(properties: valid() + overrides, headers: headers)
        script.processData(m)
        return m
    }

    String refused(Map overrides, Map headers = [:]) {
        def error = Scripts.failure { start(overrides, headers) }
        assert error instanceof IllegalStateException
        return error.message
    }

    static Map envelope(Message m) {
        return new JsonSlurper().parseText(m.getBody().toString())
    }

    // Happy path
    @Test
    void validSettingsQueueAnEnvelope() {
        def m = start()
        def e = envelope(m)
        assert e.messageId ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/
        assert m.getProperty("KEBOOLA_MESSAGE_ID") == e.messageId
        assert e.runStartedAt ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/
        assert e.loadMode == "full"
        assert e.watermark == ""
        assert e.tableId == "in.c-sap.business_partners"
        assert e.servicePath == "/sap/opu/odata/sap/API_BUSINESS_PARTNER"
        assert e.entitySet == "A_BusinessPartner"
        assert e.select == ""
        assert e.filter == ""
        assert e.deltaField == ""
        assert e.deltaFieldType == "datetime"
        assert e.deltaPrecision == "day"
        assert e.primaryKey == ""
        assert e.pageSize == PAGE_SIZE_DEFAULT
        assert e.deltaOverlapMinutes == "15"
        assert e.watermarkNote == ""
        assert e.pagingUnsorted == "true"
        assert m.getHeaders()["Content-Type"] == "application/json"
        assert m.getHeaders()["CamelHttpResponseCode"] == 202
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "QUEUED"
        def log = logs.logOf(m)
        assert log.customHeaderProperties["KeboolaMessageId"] == e.messageId
        assert log.customHeaderProperties["TableId"] == "in.c-sap.business_partners"
        assert log.customHeaderProperties["LoadMode"] == "full"
        assert log.properties["Watermark"] == "(none — full read)"
        assert log.properties["RunStartedAt"] == e.runStartedAt
        assert log.customHeaderProperties["WatermarkRead"] == null
        assert log.customHeaderProperties["PagingUnsorted"] == "true"
    }

    @Test
    void everyOptionalSettingLandsInTheEnvelope() {
        def m = start([CFG_LOAD_MODE: "Incremental", CFG_PAGE_SIZE: "250",
            CFG_ODATA_SELECT: " BusinessPartner , LastChangeDate,BusinessPartner", CFG_ODATA_FILTER: "Country eq 'CZ'",
            CFG_DELTA_FIELD: "LastChangeDate", CFG_DELTA_FIELD_TYPE: "DateTimeOffset", CFG_DELTA_PRECISION: "Second",
            CFG_PRIMARY_KEY: "BusinessPartner", CFG_HTTP_TIMEOUT_MS: "60000", CFG_DELTA_OVERLAP_MINUTES: "30"])
        def e = envelope(m)
        assert e.loadMode == "incremental"
        assert e.pageSize == "250"
        assert e.deltaOverlapMinutes == "30"
        assert e.pagingUnsorted == "false"
        assert e.select == "BusinessPartner,LastChangeDate"
        assert e.filter == "Country eq 'CZ'"
        assert e.deltaField == "LastChangeDate"
        assert e.deltaFieldType == "datetimeoffset"
        assert e.deltaPrecision == "second"
        assert e.primaryKey == "BusinessPartner"
        assert e.watermark == ""
        assert logs.logOf(m).customHeaderProperties["LoadMode"] == "incremental"
    }

    @Test
    void placeholdersCountAsUnset() {
        assert refused([CFG_KEBOOLA_TABLE_ID: "{{KEBOOLA_TABLE_ID}}"]).startsWith("CONFIG: KEBOOLA_TABLE_ID is not configured")
        def e = envelope(start([CFG_PAGE_SIZE: "{{PAGE_SIZE}}", CFG_ODATA_SELECT: "{{ODATA_SELECT}}"]))
        assert e.pageSize == PAGE_SIZE_DEFAULT
        assert e.select == ""
    }

    // Method check
    @Test
    void onlyPostMayStartARun() {
        assert refused([:], [CamelHttpMethod: "GET"]) == "METHOD: GET"
        assert refused([:], [CamelHttpMethod: " put "]) == "METHOD: PUT"
        start([:], [CamelHttpMethod: "POST"])
        start([:], [:])
    }

    // Hosts
    @Test
    void sapHostMustBeHttpsUnlessBehindTheCloudConnector() {
        assert refused([CFG_S4_HOSTNAME: ""]) == "CONFIG: S4_HOSTNAME is not configured on this integration flow."
        assert refused([CFG_S4_HOSTNAME: "http://s4.example.com"]).startsWith("CONFIG: S4_HOSTNAME must start with https://")
        assert refused([CFG_S4_HOSTNAME: "s4.example.com"]).startsWith("CONFIG: S4_HOSTNAME must start with https://")
        start([CFG_S4_HOSTNAME: "http://s4hana.virtual:44300", CFG_PROXY_TYPE: "sapcc"])
        start([CFG_S4_HOSTNAME: "https://s4hana.virtual:44300", CFG_PROXY_TYPE: "SapCC"])
        assert refused([CFG_S4_HOSTNAME: "s4hana.virtual:44300", CFG_PROXY_TYPE: "sapcc"]).contains("virtual host defined in the SAP Cloud Connector")
    }

    @Test
    void keboolaAddressesMustBeHttps() {
        assert refused([CFG_KEBOOLA_STACK_URL: ""]) == "CONFIG: KEBOOLA_STACK_URL is not configured on this integration flow."
        assert refused([CFG_KEBOOLA_STACK_URL: "http://connection.example.com"]).startsWith("CONFIG: KEBOOLA_STACK_URL must start with https://")
        assert refused([CFG_KEBOOLA_IMPORTER_URL: "http://import.example.com"]).startsWith("CONFIG: KEBOOLA_IMPORTER_URL must start with https://")
        start([CFG_KEBOOLA_IMPORTER_URL: ""])
    }

    // Service path and entity set
    @Test
    void servicePathIsNormalisedAndChecked() {
        assert envelope(start([CFG_SERVICE_PATH: "sap/opu/odata/sap/API_BUSINESS_PARTNER//"])).servicePath == "/sap/opu/odata/sap/API_BUSINESS_PARTNER"
        assert refused([CFG_SERVICE_PATH: ""]) == "CONFIG: SERVICE_PATH is not configured on this integration flow."
        assert refused([CFG_SERVICE_PATH: "https://s4.example.com/sap/opu/odata/sap/X"]).startsWith("CONFIG: SERVICE_PATH is the path of the service without the host")
        assert refused([CFG_SERVICE_PATH: "///"]).startsWith("CONFIG: SERVICE_PATH is the path of the service without the host")
        assert refused([CFG_SERVICE_PATH: "/sap/opu/odata/sap/X?y"]).startsWith("CONFIG: SERVICE_PATH must be a path without a question mark")
        assert refused([CFG_SERVICE_PATH: "/sap/opu/odata/sap/X Y"]).startsWith("CONFIG: SERVICE_PATH must be a path without a question mark")
    }

    @Test
    void entitySetIsOneNameWithoutSlashes() {
        assert envelope(start([CFG_ENTITY_SET: "/A_BusinessPartner/"])).entitySet == "A_BusinessPartner"
        assert refused([CFG_ENTITY_SET: ""]) == "CONFIG: ENTITY_SET is not configured on this integration flow."
        assert refused([CFG_ENTITY_SET: 'A_BusinessPartner?$top=1']).startsWith("CONFIG: ENTITY_SET must be the name of one collection")
        assert refused([CFG_ENTITY_SET: "A Business"]).startsWith("CONFIG: ENTITY_SET must be the name of one collection")
    }

    // Load mode, page size, timeout
    @Test
    void loadModeIsFullOrIncremental() {
        assert refused([CFG_LOAD_MODE: "delta"]) == "CONFIG: LOAD_MODE must be one of full, incremental; it is 'delta'."
        assert envelope(start([CFG_LOAD_MODE: "FULL"])).loadMode == "full"
    }

    @Test
    void pageSizeIsAWholeNumberWithinTheCeiling() {
        assert envelope(start([CFG_PAGE_SIZE: "1"])).pageSize == "1"
        assert envelope(start([CFG_PAGE_SIZE: PAGE_SIZE_CEILING.toString()])).pageSize == PAGE_SIZE_CEILING.toString()
        ["0", "-5", "1.5", "ten", (PAGE_SIZE_CEILING + 1).toString()].each { size ->
            assert refused([CFG_PAGE_SIZE: size]) == "CONFIG: PAGE_SIZE must be a whole number from 1 to " + PAGE_SIZE_CEILING + "; it is '" + size + "'."
        }
    }

    @Test
    void timeoutIsOptionalButBounded() {
        start([CFG_HTTP_TIMEOUT_MS: ""])
        start([CFG_HTTP_TIMEOUT_MS: "1000"])
        start([CFG_HTTP_TIMEOUT_MS: "3600000"])
        ["999", "3600001", "abc", "1e3", "-1000"].each { value ->
            assert refused([CFG_HTTP_TIMEOUT_MS: value]) ==
                "CONFIG: HTTP_TIMEOUT_MS must be a whole number of milliseconds from 1000 to 3600000; it is '" + value + "'."
        }
    }

    // Keys and fields
    @Test
    void primaryKeyMustBeSelected() {
        assert refused([CFG_ODATA_SELECT: "Name", CFG_PRIMARY_KEY: "Id"]) ==
            "CONFIG: PRIMARY_KEY names Id, which ODATA_SELECT does not list. Add it to ODATA_SELECT, or leave ODATA_SELECT empty to read every field."
        assert refused([CFG_ODATA_SELECT: "Name", CFG_PRIMARY_KEY: "Id,Ver"]).contains("names Id, Ver, which ODATA_SELECT does not list. Add them")
        start([CFG_ODATA_SELECT: "Id,Name", CFG_PRIMARY_KEY: "Id"])
        start([CFG_ODATA_SELECT: "", CFG_PRIMARY_KEY: "Id"])
        assert envelope(start([CFG_ODATA_SELECT: "Id,*", CFG_PRIMARY_KEY: "Name"])).select == ""
    }

    @Test
    void incrementalLoadsNeedADeltaFieldAndAKey() {
        assert refused([CFG_LOAD_MODE: "incremental"]) == "CONFIG: incremental loading needs DELTA_FIELD, the field that says when a record changed."
        assert refused([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "LastChangeDate"]) ==
            "CONFIG: incremental loading needs PRIMARY_KEY, so that records read again are updated rather than added twice."
        assert refused([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Last Change", CFG_PRIMARY_KEY: "Id"]) ==
            "CONFIG: DELTA_FIELD must be one field name, or several separated by commas; it is 'Last Change'."
        assert refused([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "1st", CFG_PRIMARY_KEY: "Id"]).startsWith("CONFIG: DELTA_FIELD must be one field name")
        assert refused([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id", CFG_DELTA_FIELD_TYPE: "timestamp"]) ==
            "CONFIG: DELTA_FIELD_TYPE must be one of datetime, datetimeoffset, date; it is 'timestamp'."
        assert refused([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id", CFG_DELTA_PRECISION: "hour"]) ==
            "CONFIG: DELTA_PRECISION must be one of day, second; it is 'hour'."
        def e = envelope(start([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Created, Changed", CFG_PRIMARY_KEY: "Id"]))
        assert e.deltaField == "Created,Changed"
        assert e.primaryKey == "Id"
    }

    @Test
    void fullLoadsSkipTheDeltaChecks() {
        def e = envelope(start([CFG_DELTA_FIELD_TYPE: "timestamp", CFG_DELTA_PRECISION: "hour"]))
        assert e.deltaFieldType == "timestamp"
        assert e.deltaPrecision == "hour"
    }

    // 1.1.0 (M6): the overlap is checked with the other incremental settings, ignored on a full load
    @Test
    void overlapIsWholeMinutesUpToAWeek() {
        def incremental = [CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id"]
        assert envelope(start(incremental + [CFG_DELTA_OVERLAP_MINUTES: "0"])).deltaOverlapMinutes == "0"
        assert envelope(start(incremental + [CFG_DELTA_OVERLAP_MINUTES: "10080"])).deltaOverlapMinutes == "10080"
        assert envelope(start(incremental + [CFG_DELTA_OVERLAP_MINUTES: "{{DELTA_OVERLAP_MINUTES}}"])).deltaOverlapMinutes == "15"
        ["-1", "10081", "1.5", "ten", "15 min"].each { value ->
            assert refused(incremental + [CFG_DELTA_OVERLAP_MINUTES: value]) ==
                "CONFIG: DELTA_OVERLAP_MINUTES must be a whole number of minutes from 0 to 10080; it is '" + value + "'."
        }
        assert envelope(start([CFG_DELTA_OVERLAP_MINUTES: "ten"])).deltaOverlapMinutes == "0"
    }

    // 1.1.0 (M6): a full load without a key pages without $orderby, and says so
    @Test
    void fullLoadWithoutAKeyIsMarkedPagingUnsorted() {
        def m = start([CFG_PRIMARY_KEY: "Id"])
        assert envelope(m).pagingUnsorted == "false"
        assert logs.logOf(m).customHeaderProperties["PagingUnsorted"] == null
        def unsorted = start([CFG_PRIMARY_KEY: ""])
        assert envelope(unsorted).pagingUnsorted == "true"
        assert logs.logOf(unsorted).customHeaderProperties["PagingUnsorted"] == "true"
    }

    // Watermark
    @Test
    void watermarkComesFromTheDataStoreWhenThereIsOne() {
        def bean = new DataBean()
        bean.setDataAsArray('{"watermark":"2026-09-01T10:00:00"}'.getBytes("UTF-8"))
        Factory.register(DataStoreService, [get: { String store, String id ->
            (store == "KeboolaDeliveryState" && id == "in.c-sap.business_partners") ? bean : null
        }] as DataStoreService)
        def m = start([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id"])
        assert envelope(m).watermark == "2026-09-01T10:00:00"
        assert logs.logOf(m).properties["Watermark"] == "2026-09-01T10:00:00"
        assert envelope(start()).watermark == ""
        assert envelope(start([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id",
            CFG_KEBOOLA_TABLE_ID: "in.c-sap.other"])).watermark == ""
    }

    // 1.1.0 (M6): a failed watermark read still means a full window, but it is visible
    @Test
    void unreadableWatermarkMeansAFullWindowAndSaysSo() {
        Factory.register(DataStoreService, [get: { String store, String id -> throw new IllegalStateException("store down") }] as DataStoreService)
        def m = start([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id"])
        def e = envelope(m)
        assert e.watermark == ""
        assert e.watermarkNote == "read failed: store down"
        def log = logs.logOf(m)
        assert log.properties["Watermark"] == "(none — full read)"
        assert log.customHeaderProperties["WatermarkRead"] == "failed, full window"
        assert log.properties["WatermarkReadError"] == "read failed: store down"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "QUEUED"
    }

    @Test
    void corruptWatermarkEntryIsAFailedRead() {
        def bean = new DataBean()
        bean.setDataAsArray("not json".getBytes("UTF-8"))
        Factory.register(DataStoreService, [get: { String store, String id -> bean }] as DataStoreService)
        def m = start([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id"])
        assert envelope(m).watermark == ""
        assert envelope(m).watermarkNote.startsWith("read failed: ")
        assert logs.logOf(m).customHeaderProperties["WatermarkRead"] == "failed, full window"
    }

    @Test
    void missingDataStoreServiceIsAFailedRead() {
        def m = start([CFG_LOAD_MODE: "incremental", CFG_DELTA_FIELD: "Changed", CFG_PRIMARY_KEY: "Id"])
        assert envelope(m).watermarkNote == "read failed: no data store service"
        assert logs.logOf(m).customHeaderProperties["WatermarkRead"] == "failed, full window"
        assert envelope(start()).watermarkNote == ""
    }
}
