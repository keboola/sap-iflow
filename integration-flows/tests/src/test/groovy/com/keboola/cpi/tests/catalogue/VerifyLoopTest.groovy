package com.keboola.cpi.tests.catalogue

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// nextCandidate.groovy and recordCandidate.groovy: one service-document call per candidate,
// the first 200 lists the service and skips its other candidates, the limits stop the loop.
class VerifyLoopTest {

    static List QUEUE = [
        [name: "API_A", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_a/srvd_a2x/sap/a/0001"],
        [name: "API_A", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_a/srvd_a2x/sap/api_a/0001"],
        [name: "API_A", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_a/srvd_a2x/sap/apia/0001"],
        [name: "API_B", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_b/srvd_a2x/sap/b/0001"],
        [name: "API_B", title: "via SAP_COM_0100", path: "/sap/opu/odata4/sap/api_b/srvd_a2x/sap/api_b/0001"],
    ]

    Script next
    Script record
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        next = Scripts.load(Scripts.CATALOGUE, "nextCandidate", logs)
        record = Scripts.load(Scripts.CATALOGUE, "recordCandidate", logs)
    }

    Message fresh(Map overrides = [:]) {
        def props = [
            CFG_S4_HOSTNAME: "https://s4.example.com", CATALOG_T0: System.currentTimeMillis().toString(), CATALOG_QUERY_EXTRA: "",
            VERIFY_QUEUE: QUEUE.collect { new HashMap(it) }, VERIFY_INDEX: "0", VERIFY_CALLS: "0", VERIFY_RESOLVED: [:],
            VERIFY_MORE: "true", CATALOG_VERIFY_LIMIT_N: "20", CATALOG_NOTES: [],
        ] + overrides
        return message(properties: props, headers: [:], body: "")
    }

    Message call(Message m, int status, String body) {
        next.processData(m)
        m.setHeader("CamelHttpResponseCode", status)
        m.setBody(body)
        record.processData(m)
        return m
    }

    @Test
    void theCallIsTheServiceDocumentOfTheCandidate() {
        def m = fresh(CATALOG_QUERY_EXTRA: "sap-client=100")
        next.processData(m)
        assert m.getProperty("S4_TARGET_PATH") == "/sap/opu/odata4/sap/api_a/srvd_a2x/sap/a/0001/"
        assert m.getProperty("S4_QUERY_STRING") == "sap-client=100"
        assert m.getHeaders()["CamelHttpMethod"] == "GET"
        assert m.getHeaders()["Accept"] == "application/json"
        assert m.getBody().toString() == ""
    }

    @Test
    void theFirstAnswerListsTheServiceAndSkipsItsOtherCandidates() {
        def m = fresh()
        call(m, 403, '{"error":{"code":"/IWBEP/CM_V4_COS/136"}}')
        assert m.getProperty("VERIFY_INDEX") == "1"
        assert m.getProperty("VERIFY_RESOLVED") == [:]
        assert m.getProperty("VERIFY_MORE") == "true"
        call(m, 200, '{"@odata.context":"$metadata","value":[{"name":"A","url":"A"}]}')
        assert m.getProperty("VERIFY_RESOLVED") == [API_A: "/sap/opu/odata4/sap/api_a/srvd_a2x/sap/api_a/0001"]
        assert m.getProperty("VERIFY_INDEX") == "3"
        assert m.getProperty("VERIFY_CALLS") == "2"
        assert m.getProperty("VERIFY_MORE") == "true"
        call(m, 404, "")
        call(m, 403, "")
        assert m.getProperty("VERIFY_INDEX") == "5"
        assert m.getProperty("VERIFY_MORE") == "false"
        assert m.getProperty("VERIFY_RESOLVED").keySet() == ["API_A"] as Set
        assert m.getProperty("CATALOG_NOTES").any { it.contains("verify API_A: /sap/opu/odata4/sap/api_a/srvd_a2x/sap/api_a/0001/ -> HTTP 200 (listed)") }
        assert m.getBody().toString() == ""
    }

    @Test
    void aWebPageWithStatus200IsNotAServiceDocument() {
        def m = call(fresh(), 200, "<html>login</html>")
        assert m.getProperty("VERIFY_RESOLVED") == [:]
        assert m.getProperty("VERIFY_INDEX") == "1"
    }

    @Test
    void theLimitStopsTheLoop() {
        def m = fresh(CATALOG_VERIFY_LIMIT_N: "2")
        call(m, 403, "")
        assert m.getProperty("VERIFY_MORE") == "true"
        call(m, 403, "")
        assert m.getProperty("VERIFY_MORE") == "false"
        assert m.getProperty("CATALOG_NOTES").any { it.contains("stopped after 2 calls") }
    }

    @Test
    void theDeadlineStopsTheLoop() {
        def m = call(fresh(CATALOG_T0: (System.currentTimeMillis() - 36000L).toString()), 403, "")
        assert m.getProperty("VERIFY_MORE") == "false"
    }
}
