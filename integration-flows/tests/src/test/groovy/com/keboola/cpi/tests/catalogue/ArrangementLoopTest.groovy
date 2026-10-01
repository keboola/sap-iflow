package com.keboola.cpi.tests.catalogue

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// nextArrangementPage.groovy and recordArrangementPage.groovy: the pages of the arrangement API,
// the next links pinned to the configured host, the three entity sets in turn, the outcomes.
class ArrangementLoopTest {

    static final String BASE = "/sap/opu/odata4/sap/aps_com_api_ca_read/srvd_a2x/sap/communicationarrangement/0001"

    Script next
    Script record
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        next = Scripts.load(Scripts.CATALOGUE, "nextArrangementPage", logs)
        record = Scripts.load(Scripts.CATALOGUE, "recordArrangementPage", logs)
    }

    Message fresh(Map overrides = [:]) {
        def props = [
            CFG_S4_HOSTNAME: "https://s4.example.com", CATALOG_SOURCE_MODE: "auto", CATALOG_T0: System.currentTimeMillis().toString(),
            ARR_MORE: "true", ARR_SET: "0", ARR_PAGE: "0", ARR_CALLS: "0", ARR_OUTCOME: "", ARR_FIRST_STATUS: "",
            ARR_NEXT_PATH: BASE + "/CommunicationArrangements", ARR_NEXT_QUERY: "", CATALOG_QUERY_EXTRA: "",
            ARR_ARRANGEMENTS: [], ARR_INBOUND: [], ARR_USERS: [], CATALOG_NOTES: [], CATALOG_TRY_GATEWAY: "false",
        ] + overrides
        return message(properties: props, headers: ["CamelHttpQuery": "debug=1", "ETag": "old"], body: "")
    }

    Message answer(Message m, int status, String body) {
        m.setHeader("CamelHttpResponseCode", status)
        m.setBody(body)
        record.processData(m)
        return m
    }

    // The request
    @Test
    void theNextPageIsACleanGetOnTheConfiguredHost() {
        def m = fresh(CATALOG_QUERY_EXTRA: "sap-client=100", CFG_AIR_KEY: "k", CFG_AIR_HEADER_NAME: "X-Air-Key")
        next.processData(m)
        assert m.getProperty("S4_TARGET_PATH") == BASE + "/CommunicationArrangements"
        assert m.getProperty("S4_QUERY_STRING") == "sap-client=100"
        assert m.getHeaders()["CamelHttpMethod"] == "GET"
        assert m.getHeaders()["Accept"] == "application/json"
        assert m.getHeaders()["CamelHttpQuery"] == null
        assert m.getHeaders()["ETag"] == null
        assert m.getHeaders()["X-Air-Key"] == "k"
        assert m.getProperty("ARR_CALLS") == "1"
        assert m.getBody().toString() == ""
    }

    @Test
    void aTrailingSlashOnTheHostDropsTheLeadingOne() {
        def m = fresh(CFG_S4_HOSTNAME: "https://s4.example.com/")
        next.processData(m)
        assert m.getProperty("S4_TARGET_PATH") == BASE.substring(1) + "/CommunicationArrangements"
    }

    // The answers
    @Test
    void pagesAreFollowedAndTheSetsReadInTurn() {
        def m = fresh()
        answer(m, 200, '{"value":[{"CommunicationArrangementUUID":"a1","CommunicationScenarioID":"SAP_COM_0008","LastChangedByUserName":"secret"}],' +
                       '"@odata.nextLink":"https://other.host:443' + BASE + '/CommunicationArrangements?$skiptoken=2"}')
        assert m.getProperty("ARR_FIRST_STATUS") == "200"
        assert m.getProperty("ARR_ARRANGEMENTS") == [[uuid: "a1", scenario: "SAP_COM_0008"]]
        // an absolute link is pinned to the configured host: only its path and query are kept
        assert m.getProperty("ARR_NEXT_PATH") == BASE + "/CommunicationArrangements"
        assert m.getProperty("ARR_NEXT_QUERY") == '$skiptoken=2'
        assert m.getProperty("ARR_PAGE") == "1"
        assert m.getProperty("ARR_MORE") == "true"
        answer(m, 200, '{"value":[{"CommunicationArrangementUUID":"a2","CommunicationScenarioName":"SAP_COM_0009"}]}')
        assert m.getProperty("ARR_ARRANGEMENTS").size() == 2
        assert m.getProperty("ARR_SET") == "1"
        assert m.getProperty("ARR_PAGE") == "0"
        assert m.getProperty("ARR_NEXT_PATH") == BASE + "/CommArrangementsInboundServ"
        assert m.getProperty("ARR_NEXT_QUERY") == ""
        // a relative link is resolved against the API's base
        answer(m, 200, '{"value":[{"CommunicationArrangementUUID":"a1","ServiceID":"API_PRODUCT_G4BA","ServiceType":"G4BA","IsHidden":false}],"@odata.nextLink":"CommArrangementsInboundServ?$skiptoken=101"}')
        assert m.getProperty("ARR_NEXT_PATH") == BASE + "/CommArrangementsInboundServ"
        assert m.getProperty("ARR_NEXT_QUERY") == '$skiptoken=101'
        assert m.getProperty("ARR_INBOUND") == [[uuid: "a1", id: "API_PRODUCT_G4BA", type: "G4BA", hidden: false]]
        answer(m, 200, '{"value":[{"CommunicationArrangementUUID":"a1","ServiceID":"X_IWSG","ServiceType":"IWSG","IsHidden":"true"}]}')
        assert m.getProperty("ARR_INBOUND")[1].hidden == true
        assert m.getProperty("ARR_SET") == "2"
        answer(m, 200, '{"value":[{"CommunicationArrangementUUID":"a1","UserName":"KEBOOLA_USER","OAuth2ClientID":"sb-1"}]}')
        assert m.getProperty("ARR_USERS") == [[uuid: "a1", user: "KEBOOLA_USER", client: "sb-1"]]
        assert m.getProperty("ARR_OUTCOME") == "OK"
        assert m.getProperty("ARR_MORE") == "false"
        assert m.getProperty("CATALOG_TRY_GATEWAY") == "false"
        assert logs.logOf(m).properties["ArrangementOutcome"] == "OK"
        assert !m.getProperty("CATALOG_NOTES").join(" ").contains("secret")
    }

    @Test
    void aMissingApiFallsThroughToTheGatewayInAutoMode() {
        def m = answer(fresh(), 404, '{"error":{}}')
        assert m.getProperty("ARR_FIRST_STATUS") == "404"
        assert m.getProperty("ARR_OUTCOME") == "HTTP_404"
        assert m.getProperty("ARR_MORE") == "false"
        assert m.getProperty("CATALOG_TRY_GATEWAY") == "true"
    }

    @Test
    void aForcedArrangementsModeDoesNotFallThrough() {
        def m = answer(fresh(CATALOG_SOURCE_MODE: "arrangements"), 403, '{"error":{}}')
        assert m.getProperty("ARR_OUTCOME") == "HTTP_403"
        assert m.getProperty("CATALOG_TRY_GATEWAY") == "false"
    }

    @Test
    void aWebPageWithStatus200IsNotJson() {
        def m = answer(fresh(), 200, "<html><body>Sign in</body></html>")
        assert m.getProperty("ARR_FIRST_STATUS") == "NOT_JSON"
        assert m.getProperty("ARR_OUTCOME") == "NOT_JSON"
        assert m.getProperty("ARR_MORE") == "false"
        assert m.getProperty("CATALOG_TRY_GATEWAY") == "true"
    }

    @Test
    void theFiftiethPageIsTheLast() {
        def m = fresh(ARR_PAGE: "49")
        answer(m, 200, '{"value":[],"@odata.nextLink":"CommunicationArrangements?$skiptoken=50"}')
        assert m.getProperty("ARR_SET") == "1"
        assert m.getProperty("CATALOG_NOTES").any { it.contains("stopped after 50 pages") }
    }

    @Test
    void theDeadlineStopsTheReadAndSkipsTheGateway() {
        def m = fresh(CATALOG_T0: (System.currentTimeMillis() - 36000L).toString())
        answer(m, 200, '{"value":[],"@odata.nextLink":"CommunicationArrangements?$skiptoken=2"}')
        assert m.getProperty("ARR_OUTCOME") == "DEADLINE"
        assert m.getProperty("ARR_MORE") == "false"
        assert m.getProperty("CATALOG_TRY_GATEWAY") == "false"
        def fine = fresh(CATALOG_T0: (System.currentTimeMillis() - 30000L).toString())
        answer(fine, 200, '{"value":[],"@odata.nextLink":"CommunicationArrangements?$skiptoken=2"}')
        assert fine.getProperty("ARR_MORE") == "true"
    }
}
