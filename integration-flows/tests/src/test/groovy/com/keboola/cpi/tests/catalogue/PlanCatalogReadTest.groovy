package com.keboola.cpi.tests.catalogue

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// planCatalogRead.groovy: every start check, the address options, the diagnostics gate, the plan.
class PlanCatalogReadTest {

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.CATALOGUE, "planCatalogRead", logs)
    }

    Message plan(Map overrides = [:], String query = "") {
        def props = [CFG_S4_HOSTNAME: "https://s4.example.com/", CFG_CREDENTIAL_ALIAS: "KEBOOLA_S4", CATALOG_QUERY: query] + overrides
        def m = message(properties: props, headers: [:], body: "")
        script.processData(m)
        return m
    }

    // The plan as shipped
    @Test
    void theShippedDefaultsPlanArrangementsFirst() {
        def m = plan()
        assert m.getProperty("CATALOG_HOST") == "https://s4.example.com"
        assert m.getProperty("CATALOG_SOURCE_MODE") == "auto"
        assert m.getProperty("CATALOG_RESOLVE_MODE") == "lookup+verify"
        assert m.getProperty("CATALOG_VERIFY_LIMIT_N") == "20"
        assert m.getProperty("CATALOG_TRY_ARRANGEMENTS") == "true"
        assert m.getProperty("CATALOG_TRY_GATEWAY") == "false"
        assert m.getProperty("ARR_MORE") == "true"
        assert m.getProperty("ARR_NEXT_PATH") == "/sap/opu/odata4/sap/aps_com_api_ca_read/srvd_a2x/sap/communicationarrangement/0001/CommunicationArrangements"
        assert m.getProperty("CATALOG_VIEW_EFFECTIVE") == "interfaces"
        assert m.getProperty("CATALOG_ODATA") == "both"
        assert m.getProperty("CATALOG_DEBUG") == "false"
        assert m.getProperty("CATALOG_QUERY_EXTRA") == ""
        assert m.getProperty("CATALOG_T0").toString().isLong()
        assert logs.logOf(m).properties["CatalogPlan"].contains("source auto")
    }

    @Test
    void gatewayModeSkipsTheArrangements() {
        def m = plan(CFG_CATALOG_SOURCE: "gateway")
        assert m.getProperty("CATALOG_TRY_ARRANGEMENTS") == "false"
        assert m.getProperty("CATALOG_TRY_GATEWAY") == "true"
        assert m.getProperty("ARR_MORE") == "false"
    }

    @Test
    void theClientNumberTravelsAsAQueryOption() {
        assert plan(CFG_SAP_CLIENT: "100").getProperty("CATALOG_QUERY_EXTRA") == "sap-client=100"
    }

    // Address options
    @Test
    void includeAndOdataAreReadFromTheAddress() {
        def m = plan([:], "include=all&odata=v4")
        assert m.getProperty("CATALOG_VIEW_EFFECTIVE") == "all"
        assert m.getProperty("CATALOG_ODATA") == "v4"
        assert plan([:], "include=bogus").getProperty("CATALOG_VIEW_EFFECTIVE") == "interfaces"
        assert plan([CFG_CATALOG_VIEW: "extended"], "include=bogus").getProperty("CATALOG_VIEW_EFFECTIVE") == "extended"
    }

    @Test
    void theOldSupportSwitchesAreIgnored() {
        def m = plan([:], "mode=gateway-cc&nocache=1&filter=none")
        assert m.getProperty("CATALOG_TRY_ARRANGEMENTS") == "true"
        assert m.getProperty("CATALOG_ROUTE") == null
        assert m.getProperty("CATALOG_DEBUG") == "false"
    }

    @Test
    void debugAnswersOnlyWhenDiagnosticsAreSwitchedOn() {
        assert plan([:], "debug=1").getProperty("CATALOG_DEBUG") == "false"
        assert plan([CFG_DIAGNOSTICS: "false"], "debug=1").getProperty("CATALOG_DEBUG") == "false"
        assert plan([CFG_DIAGNOSTICS: "true"], "debug=1").getProperty("CATALOG_DEBUG") == "true"
        assert plan([CFG_DIAGNOSTICS: "true"], "debug=true").getProperty("CATALOG_DEBUG") == "true"
        assert plan([CFG_DIAGNOSTICS: "true"], "").getProperty("CATALOG_DEBUG") == "false"
    }

    // Start checks
    @Test
    void aRejectedMethodIsThrownFirst() {
        def e = Scripts.failure { plan(KEBOOLA_REJECT_REASON: "METHOD_NOT_ALLOWED", KEBOOLA_REJECT_DETAIL: "POST") }
        assert e.message == "HTTP method POST is not supported by this endpoint. Allowed: GET, HEAD."
    }

    @Test
    void theAddressNeedsItsProtocolAndHttps() {
        assert Scripts.failure { plan(CFG_S4_HOSTNAME: "") }.message == "CONFIG: S4_HOSTNAME is not configured on this integration flow."
        assert Scripts.failure { plan(CFG_S4_HOSTNAME: "{{S4_HOSTNAME}}") }.message.startsWith("CONFIG: S4_HOSTNAME is not configured")
        assert Scripts.failure { plan(CFG_S4_HOSTNAME: "s4.example.com") }.message.startsWith("CONFIG: S4_HOSTNAME must be written with its protocol")
        assert Scripts.failure { plan(CFG_S4_HOSTNAME: "http://s4.example.com") }.message.startsWith("CONFIG: S4_HOSTNAME must start with https://")
        // behind a Cloud Connector the virtual host is written with http://
        assert plan(CFG_S4_HOSTNAME: "http://s4h.virtual:44300", CFG_PROXY_TYPE: "sapcc").getProperty("CATALOG_HOST") == "http://s4h.virtual:44300"
    }

    @Test
    void theCredentialAliasIsNeededUnlessACertificateSignsIn() {
        assert Scripts.failure { plan(CFG_CREDENTIAL_ALIAS: "") }.message == "CONFIG: S4_CREDENTIAL_ALIAS is not configured on this integration flow."
        assert plan(CFG_CREDENTIAL_ALIAS: "", CFG_AUTH_METHOD: "Client Certificate").getProperty("CATALOG_HOST") != null
    }

    @Test
    void everySwitchIsChecked() {
        assert Scripts.failure { plan(CFG_TIMEOUT_MS: "0") }.message.startsWith("CONFIG: HTTP_TIMEOUT_MS must be a whole number")
        assert Scripts.failure { plan(CFG_TIMEOUT_MS: "soon") }.message.startsWith("CONFIG: HTTP_TIMEOUT_MS")
        assert Scripts.failure { plan(CFG_CATALOG_VIEW: "everything") }.message == "CONFIG: CATALOG_VIEW must be one of interfaces, extended, all - got 'everything'."
        assert Scripts.failure { plan(CFG_CATALOG_SOURCE: "cache") }.message.startsWith("CONFIG: CATALOG_SOURCE must be one of auto, arrangements, gateway")
        assert Scripts.failure { plan(CFG_RESOLVE_V4: "probe") }.message.startsWith("CONFIG: CATALOG_RESOLVE_V4 must be one of lookup, lookup+verify, off")
        assert Scripts.failure { plan(CFG_VERIFY_LIMIT: "-1") }.message.startsWith("CONFIG: CATALOG_VERIFY_LIMIT must be a whole number from 0 to 200")
        assert Scripts.failure { plan(CFG_VERIFY_LIMIT: "201") }.message.startsWith("CONFIG: CATALOG_VERIFY_LIMIT")
        assert Scripts.failure { plan(CFG_DIAGNOSTICS: "yes") }.message.startsWith("CONFIG: CATALOG_DIAGNOSTICS must be one of true, false")
        assert plan(CFG_VERIFY_LIMIT: "0").getProperty("CATALOG_VERIFY_LIMIT_N") == "0"
        assert plan(CFG_RESOLVE_V4: "Lookup+Verify").getProperty("CATALOG_RESOLVE_MODE") == "lookup+verify"
    }
}
