package com.keboola.cpi.tests.connector

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// prepareS4Request.groovy: turns the incoming request into the receiver's target path and
// query, or throws for a request the first step rejected.
class PrepareS4RequestTest {

    Script script

    @Before
    void load() {
        script = Scripts.load(Scripts.CONNECTOR, "prepareS4Request")
    }

    Message prepared(Map args = [:]) {
        def properties = [CFG_S4_HOSTNAME: "https://my123456-api.s4hana.cloud.sap"]
        def headers = [CamelHttpMethod: "GET", CamelHttpPath: "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner",
                       CamelHttpUrl: "https://tenant.example/http/keboola/connector/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner",
                       CamelHttpQuery: "\$top=1"]
        def m = message(properties: properties + (args.properties ?: [:]), headers: headers + (args.headers ?: [:]))
        script.processData(m)
        return m
    }

    // Rejections
    @Test
    void aRejectedPathThrowsNamingTheParameter() {
        def error = Scripts.failure {
            prepared(properties: [KEBOOLA_REJECT_REASON: "PATH_NOT_ALLOWED", KEBOOLA_REJECT_DETAIL: "/sap/bc/ping"])
        }
        assert error instanceof IllegalStateException
        assert error.message == "PATH: /sap/bc/ping is outside CONNECTOR_PATH_PREFIXES."
        assert Scripts.failure { prepared(properties: [KEBOOLA_REJECT_REASON: "PATH_NOT_ALLOWED"]) }.message ==
            "PATH: unknown is outside CONNECTOR_PATH_PREFIXES."
    }

    @Test
    void aRejectedMethodAndAConfigurationErrorThrowTheirTexts() {
        assert Scripts.failure { prepared(properties: [KEBOOLA_REJECT_REASON: "METHOD_NOT_ALLOWED", KEBOOLA_REJECT_DETAIL: "DELETE"]) }.message ==
            "HTTP method DELETE is not supported by this connector. Allowed: GET, HEAD."
        assert Scripts.failure { prepared(properties: [KEBOOLA_REJECT_REASON: "CONFIG_ERROR", KEBOOLA_REJECT_DETAIL: "S4_HOSTNAME is not configured on this integration flow."]) }.message ==
            "CONFIG: S4_HOSTNAME is not configured on this integration flow."
        assert Scripts.failure { prepared(properties: [KEBOOLA_REJECT_REASON: "CONFIG_ERROR"]) }.message ==
            "CONFIG: this integration flow is not configured correctly."
    }

    // The forwarded request
    @Test
    void targetPathAndMethodComeFromTheRequest() {
        def m = prepared()
        assert m.getProperty("S4_TARGET_PATH") == "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
        assert m.getProperty("S4_HTTP_METHOD") == "GET"
        assert m.getProperty("S4_QUERY_STRING") == "\$top=1"
        assert m.getHeaders().containsKey("CamelHttpPath") && m.getHeaders()["CamelHttpPath"] == null
        assert m.getHeaders().containsKey("CamelHttpQuery") && m.getHeaders()["CamelHttpQuery"] == null
        assert prepared(headers: [CamelHttpMethod: "head"]).getProperty("S4_HTTP_METHOD") == "HEAD"
        assert prepared(headers: [CamelHttpPath: "sap/opu/odata/x"]).getProperty("S4_TARGET_PATH") == "/sap/opu/odata/x"
        // a slash at the end of the host is tolerated: the path then carries none of its own
        assert prepared(properties: [CFG_S4_HOSTNAME: "https://s4.example/"]).getProperty("S4_TARGET_PATH") == "sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
    }

    @Test
    void escapedCharactersInThePathAreForwardedAsTheCallerSentThem() {
        def m = prepared(headers: [CamelHttpPath: "/sap/opu/odata/sap/API_X/A_Y('a b')",
                                   CamelHttpUrl: "https://tenant.example/http/keboola/connector/sap/opu/odata/sap/API_X/A_Y('a%20b')"])
        assert m.getProperty("S4_TARGET_PATH") == "/sap/opu/odata/sap/API_X/A_Y('a%20b')"
    }

    @Test
    void sapClientIsAddedOnceAndOnlyWhenConfigured() {
        assert prepared(properties: [CFG_SAP_CLIENT: "100"]).getProperty("S4_QUERY_STRING") == "\$top=1&sap-client=100"
        assert prepared(properties: [CFG_SAP_CLIENT: "100"], headers: [CamelHttpQuery: null]).getProperty("S4_QUERY_STRING") == "sap-client=100"
        assert prepared(properties: [CFG_SAP_CLIENT: "100"], headers: [CamelHttpQuery: "sap-client=200"]).getProperty("S4_QUERY_STRING") == "sap-client=200"
        assert prepared(properties: [CFG_SAP_CLIENT: "{{S4_SAP_CLIENT}}"]).getProperty("S4_QUERY_STRING") == "\$top=1"
    }

    @Test
    void integrationKeyHeaderNeedsBothNameAndKey() {
        assert prepared(properties: [CFG_AIR_KEY: "k", CFG_AIR_HEADER_NAME: "X-Air-Key"]).getHeaders()["X-Air-Key"] == "k"
        assert prepared(properties: [CFG_AIR_KEY: "k"]).getHeaders()["X-Air-Key"] == null
        assert prepared(properties: [CFG_AIR_HEADER_NAME: "X-Air-Key"]).getHeaders()["X-Air-Key"] == null
    }
}
