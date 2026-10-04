package com.keboola.cpi.tests.delivery

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// inspectPage.groovy: the verdict on one SAP answer — ok, failed for good, or retry.
class InspectPageTest {

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.DELIVERY, "inspectPage", logs)
    }

    static Message answer(Object status, String contentType, String body = "") {
        def headers = [:]
        if (status != null) { headers["CamelHttpResponseCode"] = status }
        if (contentType != null) { headers["Content-Type"] = contentType }
        return message(properties: [PAGE_NUMBER: "3"], headers: headers, body: body)
    }

    // 2xx
    @Test
    void jsonAnswerIsOk() {
        [200, "200", 201, 204].each { status ->
            def m = answer(status, "application/json; charset=utf-8", '{"d":{}}')
            script.processData(m)
            assert m.getProperty("PAGE_VERDICT") == "ok"
            assert m.getProperty("RUN_FAILED") == null
            assert m.getBody() == '{"d":{}}'
            assert logs.logOf(m).customHeaderProperties.isEmpty()
        }
    }

    @Test
    void answerWithoutContentTypeIsOk() {
        def m = answer(200, null, '{"d":{}}')
        script.processData(m)
        assert m.getProperty("PAGE_VERDICT") == "ok"
    }

    @Test
    void htmlOrXmlAnswerIsAWrongHost() {
        ["text/html; charset=utf-8", "application/xml", "TEXT/HTML", "application/atom+xml"].each { type ->
            def m = answer(200, type, "<html>sign in</html>")
            script.processData(m)
            assert m.getProperty("PAGE_VERDICT") == "failed"
            assert m.getProperty("RUN_FAILED") == "UPSTREAM_FAILED"
            assert m.getProperty("RUN_FAILED_DETAIL").contains("page 3")
            assert m.getProperty("RUN_FAILED_DETAIL").contains("instead of JSON")
            assert m.getProperty("RUN_FAILED_DETAIL").contains("S4_HOSTNAME")
            assert m.getProperty("MORE_PAGES") == "false"
            assert m.getBody() == ""
            def log = logs.logOf(m)
            assert log.customHeaderProperties["UpstreamStatus"] == "200"
            assert log.customHeaderProperties["UpstreamOutcome"] == "FAILED"
            assert log.customHeaderProperties["UpstreamError"] == "<html>sign in</html>"
        }
    }

    // 4xx: final
    @Test
    void clientErrorsEndTheRun() {
        [400, 401, 403, 404, 405, 415].each { status ->
            def m = answer(status, "application/json", '{"error":{"message":{"value":"denied"}}}')
            script.processData(m)
            assert m.getProperty("PAGE_VERDICT") == "failed"
            assert m.getProperty("RUN_FAILED") == "UPSTREAM_FAILED"
            assert m.getProperty("RUN_FAILED_DETAIL").startsWith("HTTP " + status + " from SAP S/4HANA on page 3: ")
            assert m.getProperty("RUN_FAILED_DETAIL").contains("denied")
            assert m.getProperty("MORE_PAGES") == "false"
            assert m.getBody() == ""
            assert logs.logOf(m).customHeaderProperties["UpstreamOutcome"] == "FAILED"
            assert logs.logOf(m).customHeaderProperties["UpstreamStatus"] == status.toString()
        }
    }

    // 5xx, 429, 408, no status: transient
    @Test
    void serverErrorsAndThrottlingAreRetried() {
        [500, 502, 503, 504, 429, 408, 0, "not-a-number"].each { status ->
            def m = answer(status, "text/plain", "try later")
            def error = Scripts.failure { script.processData(m) }
            assert error instanceof IllegalStateException
            assert error.message.startsWith("UPSTREAM_TRANSIENT: HTTP ")
            assert error.message.contains("on page 3: try later")
            assert error.message.contains("retried")
            assert m.getProperty("RUN_FAILED") == null
            assert m.getBody() == "try later"
            assert logs.logOf(m).customHeaderProperties["UpstreamOutcome"] == "RETRY"
        }
    }

    @Test
    void missingStatusIsTransientAndSaysSo() {
        def m = answer(null, "text/plain", "")
        def error = Scripts.failure { script.processData(m) }
        assert error.message.startsWith("UPSTREAM_TRANSIENT: HTTP (no status) from SAP S/4HANA on page 3: ")
        assert logs.logOf(m).customHeaderProperties["UpstreamStatus"] == "unknown"
        assert logs.logOf(m).customHeaderProperties["UpstreamError"] == "(no body)"
    }

    @Test
    void worksWithoutAMessageLog() {
        logs.setAvailable(false)
        def m = answer(404, "text/plain", "gone")
        script.processData(m)
        assert m.getProperty("RUN_FAILED") == "UPSTREAM_FAILED"
        assert logs.logOf(m).customHeaderProperties.isEmpty()
    }

    // excerpt, record, contentTypeOf
    @Test
    void errorExcerptIsTrimmedForTheMonitor() {
        def m = answer(404, "text/plain", "  many   words\n\n" + ("x" * 300))
        script.processData(m)
        def logged = logs.logOf(m).customHeaderProperties["UpstreamError"]
        assert logged.startsWith("many words xxx")
        assert logged.length() <= 200
        assert m.getProperty("RUN_FAILED_DETAIL").length() < 400
    }

    @Test
    void longBodiesAreCutAtEightKilobytes() {
        def m = answer(200, "text/plain", "x" * 9000)
        assert script.excerpt(m).length() == 8192 + "\n… truncated".length()
        assert script.excerpt(message(body: "short")) == "short"
        assert script.excerpt(message(body: null)) == ""
    }

    @Test
    void contentTypeLookupIgnoresCase() {
        assert script.contentTypeOf(["content-type": " text/html "]) == "text/html"
        assert script.contentTypeOf(["Content-Type": "application/json"]) == "application/json"
        assert script.contentTypeOf([Other: "x"]) == ""
        assert script.contentTypeOf([:]) == ""
    }
}
