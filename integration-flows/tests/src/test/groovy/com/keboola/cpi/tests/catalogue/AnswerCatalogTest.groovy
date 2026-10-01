package com.keboola.cpi.tests.catalogue

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import groovy.json.JsonSlurper
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// answerCatalog.groovy: the views, the sort order, the verified guesses, the unlisted names,
// the response headers, the diagnostics gate and the monitor fields.
class AnswerCatalogTest {

    static final String HOST = "https://s4.example.com"

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.CATALOGUE, "answerCatalog", logs)
    }

    static Map row(String name, String title, String path, Map extra = [:]) {
        return [ID: name, Title: name, Description: title, ServiceUrl: HOST + path, MetadataUrl: HOST + path + "/\$metadata"] + extra
    }

    static List CLOUD_ROWS = [
        row("API_PRODUCT", "Product (A2X)", "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0001"),
        row("API_BUSINESS_PARTNER", "Business Partner (A2X)", "/sap/opu/odata/sap/API_BUSINESS_PARTNER"),
        row("ZCUSTOM_SRV", "Mine", "/sap/opu/odata/sap/ZCUSTOM_SRV"),
    ]

    Message answer(Map overrides = [:]) {
        def props = [
            CATALOG_RUN_ID: "AGq7-run", CATALOG_HOST: HOST, CATALOG_SOURCE_USED: "arrangements",
            CATALOG_VIEW_EFFECTIVE: "interfaces", CATALOG_ODATA: "both", CATALOG_DEBUG: "false",
            CATALOG_ROWS: CLOUD_ROWS.collect { new HashMap(it) }, VERIFY_QUEUE: [], VERIFY_RESOLVED: [:],
            CATALOG_UNLISTED: [], CATALOG_NOTES: [], CATALOG_T0: "0", VERIFY_CALLS: "0", CATALOG_STATS: [:],
            CFG_AIR_HEADER_NAME: "X-Air-Key",
        ] + overrides
        def m = message(properties: props, headers: ["X-Air-Key": "secret", "ETag": "abc", "CamelHttpResponseCode": 200], body: "")
        script.processData(m)
        return m
    }

    static List results(Message m) { return new JsonSlurper().parseText(m.getBody().toString()).d.results }

    static Map parsed(Message m) { return new JsonSlurper().parseText(m.getBody().toString()).d }

    // Sort and markers
    @Test
    void apiServicesComeFirstThenTheRestAlphabetically() {
        def m = answer()
        assert results(m).collect { it.ID } == ["API_BUSINESS_PARTNER", "API_PRODUCT", "ZCUSTOM_SRV"]
        assert results(m)[0].Description == "Business Partner (A2X) (OData V2)"
        assert results(m)[1].Description == "Product (A2X) (OData V4)"
        assert m.getHeaders()["CamelHttpResponseCode"] == 200
        assert m.getHeaders()["Content-Type"] == "application/json"
        assert m.getHeaders()["X-Keboola-Catalog-Source"] == "arrangements"
        assert m.getHeaders()["X-Keboola-Catalog-View"] == "interfaces;shown=3;hidden=0"
        assert m.getHeaders()["X-Keboola-Catalog-Unlisted"] == "0"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "OK"
        assert parsed(m).diagnostics == null
    }

    @Test
    void nothingOfSapsAnswerOrTheIntegrationKeyReachesTheCaller() {
        def m = answer()
        assert m.getHeaders()["X-Air-Key"] == null
        assert m.getHeaders()["ETag"] == null
    }

    // OData version knob
    @Test
    void theOdataKnobKeepsOneKind() {
        def v2 = answer(CATALOG_ODATA: "v2")
        assert results(v2).collect { it.ID } == ["API_BUSINESS_PARTNER", "ZCUSTOM_SRV"]
        assert v2.getHeaders()["X-Keboola-Catalog-View"] == "interfaces;shown=2;hidden=1;odata=v2"
        def v4 = answer(CATALOG_ODATA: "v4")
        assert results(v4).collect { it.ID } == ["API_PRODUCT"]
    }

    // Views on Gateway rows
    static List GATEWAY_ROWS = [
        row("ZAPI_BUSINESS_PARTNER_0001", "Business Partner", "/sap/opu/odata/sap/API_BUSINESS_PARTNER", [ServiceType: "WEB_API", IsSapService: "true", TechnicalServiceName: "API_BUSINESS_PARTNER"]),
        row("ZUI_THING_0001", "Fiori", "/sap/opu/odata/sap/UI_THING", [ServiceType: "UI", IsSapService: "true", TechnicalServiceName: "UI_THING"]),
        row("ZOTHER_SRV_0001", "Other", "/sap/opu/odata/sap/OTHER_SRV", [ServiceType: "OTHER", IsSapService: "true", TechnicalServiceName: "OTHER_SRV"]),
        row("ZMINE_SRV_0001", "Mine", "/sap/opu/odata/sap/ZMINE_SRV", [ServiceType: "OTHER", IsSapService: "false", TechnicalServiceName: "ZMINE_SRV"]),
        row("I_RAWVIEW", "Raw view", "/sap/opu/odata4/sap/i_rawview/srvd/sap/i_rawview/0001"),
        row("ZMY_V4", "My view", "/sap/opu/odata4/sap/zmy_v4/srvd/sap/zmy_v4/0001"),
        row("API_RELEASED", "Released", "/sap/opu/odata4/sap/api_released/srvd_a2x/sap/released/0001", [ReleaseStatus: "DEPRECATED"]),
    ]

    // The registration prefix (ZAPI_…_0001) is stripped for the sort, so Business Partner is an API_ row
    @Test
    void theInterfacesViewKeepsWebApisOwnServicesAndReleasedV4() {
        def m = answer(CATALOG_ROWS: GATEWAY_ROWS.collect { new HashMap(it) }, CATALOG_SOURCE_USED: "gateway")
        assert results(m).collect { it.ID } == ["ZAPI_BUSINESS_PARTNER_0001", "API_RELEASED", "ZMINE_SRV_0001", "ZMY_V4"]
        assert results(m)[1].Description == "Released (OData V4) (deprecated)"
        assert m.getHeaders()["X-Keboola-Catalog-View"] == "interfaces;shown=4;hidden=3"
    }

    @Test
    void theViewsNest() {
        def extended = answer(CATALOG_ROWS: GATEWAY_ROWS.collect { new HashMap(it) }, CATALOG_VIEW_EFFECTIVE: "extended")
        assert results(extended).collect { it.ID } == ["ZAPI_BUSINESS_PARTNER_0001", "API_RELEASED", "ZMINE_SRV_0001", "ZMY_V4", "ZOTHER_SRV_0001"]
        def all = answer(CATALOG_ROWS: GATEWAY_ROWS.collect { new HashMap(it) }, CATALOG_VIEW_EFFECTIVE: "all")
        assert results(all).size() == 7
        assert all.getHeaders()["X-Keboola-Catalog-View"] == "all;shown=7;hidden=0"
    }

    // Verified guesses and unlisted names
    @Test
    void verifiedGuessesJoinTheListAndTheRestIsNamed() {
        def queue = [
            [name: "API_FOUND", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_found/srvd_a2x/sap/found/0001"],
            [name: "API_FOUND", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_found/srvd_a2x/sap/api_found/0001"],
            [name: "API_LOST", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_lost/srvd_a2x/sap/lost/0001"],
            [name: "API_NEVER_TRIED", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_never_tried/srvd_a2x/sap/nevertried/0001"],
        ]
        def m = answer(VERIFY_QUEUE: queue, VERIFY_RESOLVED: [API_FOUND: "/sap/opu/odata4/sap/api_found/srvd_a2x/sap/api_found/0001"],
                       CATALOG_UNLISTED: ["API_OFF"], VERIFY_CALLS: "3", CATALOG_DEBUG: "true")
        assert results(m).find { it.ID == "API_FOUND" }.ServiceUrl == HOST + "/sap/opu/odata4/sap/api_found/srvd_a2x/sap/api_found/0001"
        assert results(m).find { it.ID == "API_FOUND" }.Description == "via SAP_COM_0100 (OData V4)"
        assert results(m).collect { it.ID } == ["API_BUSINESS_PARTNER", "API_FOUND", "API_PRODUCT", "ZCUSTOM_SRV"]
        assert m.getHeaders()["X-Keboola-Catalog-Unlisted"] == "3"
        assert parsed(m).diagnostics.unlisted == ["API_LOST", "API_NEVER_TRIED", "API_OFF"]
        assert parsed(m).diagnostics.verification == [calls: 3, resolved: 1]
        assert parsed(m).diagnostics.messageId == "AGq7-run"
        def log = logs.logOf(m)
        assert log.customHeaderProperties["CatalogUnlisted"] == "3"
        assert log.customHeaderProperties["CatalogUnlistedNames"] == "API_LOST, API_NEVER_TRIED, API_OFF"
        assert log.properties["CatalogUnlisted"] == "API_LOST, API_NEVER_TRIED, API_OFF"
        assert log.customHeaderProperties["CatalogCount"] == "4"
        assert log.properties["CatalogDiagnostics"].contains("unlisted: 3 OData V4 service(s)")
    }

    @Test
    void anEmptyListIsCatalogEmpty() {
        def m = answer(CATALOG_ROWS: [])
        assert results(m) == []
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "CATALOG_EMPTY"
        assert logs.logOf(m).properties["CatalogUnlisted"] == "none"
        assert logs.logOf(m).customHeaderProperties["CatalogUnlistedNames"] == null
    }

    // The diagnostics gate: the plan decides, the answer only follows
    @Test
    void diagnosticsAppearOnlyWhenThePlanAllowedThem() {
        assert parsed(answer(CATALOG_DEBUG: "false")).diagnostics == null
        assert parsed(answer(CATALOG_DEBUG: "true")).diagnostics.source == "arrangements"
    }
}
