package com.keboola.cpi.tests.connector

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// logIncoming.groovy: the connector's first step — method, host and path checks, the masked
// header summary, the monitor fields.
class LogIncomingTest {

    static final String PREFIXES = "/sap/opu/odata/,/sap/opu/odata4/"

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.CONNECTOR, "logIncoming", logs)
    }

    Message incoming(Map args = [:]) {
        def properties = [CFG_S4_HOSTNAME: "https://my123456-api.s4hana.cloud.sap", CFG_PATH_PREFIXES: PREFIXES]
        def headers = [CamelHttpMethod: "GET", CamelHttpPath: "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"]
        def m = message(properties: properties + (args.properties ?: [:]), headers: headers + (args.headers ?: [:]))
        script.processData(m)
        return m
    }

    // Path normalisation
    @Test
    void pathsAreNormalisedBeforeTheyAreChecked() {
        assert script.normalizePath("") == "/"
        assert script.normalizePath(null) == "/"
        assert script.normalizePath("sap/opu/odata/x") == "/sap/opu/odata/x"
        assert script.normalizePath("/sap/opu/odata/x") == "/sap/opu/odata/x"
        assert script.normalizePath("/a/./b//c/") == "/a/b/c"
        assert script.normalizePath("/sap/opu/odata/../../bc/ping") == "/sap/bc/ping"
        assert script.normalizePath("/../../x") == "/x"
        assert script.normalizePath("/sap/opu/odata/sap/API_X/A_Y('1')") == "/sap/opu/odata/sap/API_X/A_Y('1')"
    }

    // Prefix rule
    @Test
    void shippedPrefixesCoverTheODataRootsAndNothingElse() {
        assert script.pathAllowed("/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner", PREFIXES)
        assert script.pathAllowed("sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0001/Product", PREFIXES)
        assert script.pathAllowed("/sap/opu/odata/sap/API_BUSINESS_PARTNER/\$metadata", PREFIXES)
        assert script.pathAllowed("/sap/opu/odata", PREFIXES)
        assert script.pathAllowed("/sap/opu/odata4/", PREFIXES)
        assert !script.pathAllowed("/sap/bc/ping", PREFIXES)
        assert !script.pathAllowed("/sap/opu/odata/../../bc/ping", PREFIXES)
        assert !script.pathAllowed("/sap/opu/odataX/sap/x", PREFIXES)
        assert !script.pathAllowed("/sap/opu/odat", PREFIXES)
        assert !script.pathAllowed("/", PREFIXES)
        assert !script.pathAllowed("", PREFIXES)
    }

    @Test
    void emptyPrefixesForwardEveryPath() {
        ["", null, " ", ","].each { prefixes ->
            assert script.pathAllowed("/sap/bc/ping", prefixes)
            assert script.pathAllowed("/", prefixes)
        }
    }

    @Test
    void prefixesAreTrimmedAndGetTheirLeadingSlash() {
        assert script.pathAllowed("/sap/bc/ping", " sap/bc/ , /other/")
        assert script.pathAllowed("/other/x", " sap/bc/ , /other/")
        assert !script.pathAllowed("/sap/opu/odata/sap/x", " sap/bc/ , /other/")
        assert script.pathAllowed("/custom/svc/Set", "/custom/svc")
    }

    // The checks, in their order
    @Test
    void aPathOutsideThePrefixesIsRejectedWithItsNormalisedForm() {
        def m = incoming(headers: [CamelHttpPath: "/sap/opu/odata/../../bc/ping"])
        assert m.getProperty("KEBOOLA_REJECT_REASON") == "PATH_NOT_ALLOWED"
        assert m.getProperty("KEBOOLA_REJECT_DETAIL") == "/sap/bc/ping"
        def ok = incoming()
        assert ok.getProperty("KEBOOLA_REJECT_REASON") == null
        assert ok.getProperty("KEBOOLA_REJECT_DETAIL") == null
    }

    @Test
    void anUnsetOrPlaceholderPrefixListForwardsEveryPath() {
        assert incoming(properties: [CFG_PATH_PREFIXES: ""], headers: [CamelHttpPath: "/sap/bc/ping"]).getProperty("KEBOOLA_REJECT_REASON") == null
        assert incoming(properties: [CFG_PATH_PREFIXES: "{{CONNECTOR_PATH_PREFIXES}}"], headers: [CamelHttpPath: "/sap/bc/ping"]).getProperty("KEBOOLA_REJECT_REASON") == null
    }

    @Test
    void theMethodCheckComesBeforeThePathCheck() {
        def m = incoming(headers: [CamelHttpMethod: "POST", CamelHttpPath: "/sap/bc/ping"])
        assert m.getProperty("KEBOOLA_REJECT_REASON") == "METHOD_NOT_ALLOWED"
        assert m.getProperty("KEBOOLA_REJECT_DETAIL") == "POST"
        assert incoming(headers: [CamelHttpMethod: "head"]).getProperty("KEBOOLA_REJECT_REASON") == null
        assert incoming(headers: [CamelHttpMethod: null]).getProperty("KEBOOLA_REJECT_REASON") == null
    }

    @Test
    void theHostCheckComesBeforeThePathCheck() {
        def missing = incoming(properties: [CFG_S4_HOSTNAME: ""], headers: [CamelHttpPath: "/sap/bc/ping"])
        assert missing.getProperty("KEBOOLA_REJECT_REASON") == "CONFIG_ERROR"
        assert missing.getProperty("KEBOOLA_REJECT_DETAIL") == "S4_HOSTNAME is not configured on this integration flow."
        def plain = incoming(properties: [CFG_S4_HOSTNAME: "http://s4.example"])
        assert plain.getProperty("KEBOOLA_REJECT_REASON") == "CONFIG_ERROR"
        assert plain.getProperty("KEBOOLA_REJECT_DETAIL").startsWith("S4_HOSTNAME must start with https://")
        assert plain.getProperty("KEBOOLA_REJECT_DETAIL").endsWith("starts with 'http://s'.")
    }

    @Test
    void cloudConnectorAddressesTakeHttpOnly() {
        assert incoming(properties: [CFG_S4_HOSTNAME: "http://s4hana.virtual:44300", CFG_PROXY_TYPE: "sapcc"]).getProperty("KEBOOLA_REJECT_REASON") == null
        def noProtocol = incoming(properties: [CFG_S4_HOSTNAME: "s4hana.virtual:44300", CFG_PROXY_TYPE: "sapcc"])
        assert noProtocol.getProperty("KEBOOLA_REJECT_REASON") == "CONFIG_ERROR"
        assert noProtocol.getProperty("KEBOOLA_REJECT_DETAIL").startsWith("S4_HOSTNAME must start with http:// followed by the virtual host")
        // https through the tunnel is left to the platform's own refusal (F-33), not this step's
        assert incoming(properties: [CFG_S4_HOSTNAME: "https://s4hana.virtual:44300", CFG_PROXY_TYPE: "sapcc"]).getProperty("KEBOOLA_REJECT_REASON") == null
    }

    // Header summary and monitor
    @Test
    void sensitiveHeadersAndQueryParametersAreMasked() {
        def m = incoming(headers: [Authorization: "Bearer x", "X-Api-Key": "k", Accept: "application/json",
                                   CamelHttpQuery: "\$top=1&token=abc&sap-client=100&api_key=z"])
        def summary = m.getProperty("KEBOOLA_INCOMING_HEADERS").toString()
        assert summary.contains("Authorization: ***redacted***")
        assert summary.contains("X-Api-Key: ***redacted***")
        assert summary.contains("Accept: application/json")
        assert summary.contains("CamelHttpQuery: \$top=1&token=***redacted***&sap-client=100&api_key=***redacted***")
        assert !summary.contains("Bearer x")
        assert !summary.contains("abc")
        assert script.redactQuery(null, ["token"]) == null
        assert script.redactQuery("a=1&flag", ["token"]) == "a=1&flag"
        assert script.redactQuery("Token=1", ["token"]) == "Token=***redacted***"
    }

    @Test
    void monitorGetsMethodPathAndMaskedQuery() {
        def m = incoming(headers: [CamelHttpQuery: "\$top=1&password=p"])
        def log = logs.logOf(m)
        assert log.customHeaderProperties["HttpPath"] == "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
        assert log.properties["HttpMethod"] == "GET"
        assert log.properties["HttpPath"] == "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
        assert log.properties["QueryString"] == "\$top=1&password=***redacted***"
        def bare = incoming(headers: [CamelHttpPath: null, CamelHttpQuery: null])
        assert logs.logOf(bare).customHeaderProperties["HttpPath"] == "N/A"
        assert logs.logOf(bare).properties["QueryString"] == "N/A"
    }

    @Test
    void worksWithoutAMessageLog() {
        logs.setAvailable(false)
        def m = incoming(headers: [CamelHttpPath: "/sap/bc/ping"])
        assert m.getProperty("KEBOOLA_REJECT_REASON") == "PATH_NOT_ALLOWED"
        assert m.getProperty("KEBOOLA_INCOMING_HEADERS") != null
    }
}
