package com.keboola.cpi.tests.connector

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import groovy.json.JsonSlurper
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// handleException.groovy: the connector's error answer — status, code, body, monitor fields.
class HandleExceptionTest {

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.CONNECTOR, "handleException", logs)
    }

    Message failed(Throwable cause, Map properties = [:]) {
        def m = message(properties: [SAP_MessageProcessingLogID: "AGq7-run"] + properties)
        if (cause != null) { m.setProperty("CamelExceptionCaught", cause) }
        script.processData(m)
        return m
    }

    static Map error(Message m) {
        return new JsonSlurper().parseText(m.getBody().toString()).error
    }

    // Code mapping
    @Test
    void unknownFailureIsUpstreamUnreachable() {
        def m = failed(new java.net.ConnectException("Connection refused"))
        assert m.getHeaders()["CamelHttpResponseCode"] == 502
        assert m.getHeaders()["Content-Type"] == "application/json"
        assert m.getHeaders()["Allow"] == null
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "UPSTREAM_UNREACHABLE"
        def e = error(m)
        assert e.code == "UPSTREAM_UNREACHABLE"
        assert e.message == "Connection refused"
        assert e.messageId == "AGq7-run"
        assert e.timestamp ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{4}/
    }

    @Test
    void noExceptionAtAllIsStillAnAnswer() {
        def m = failed(null, [SAP_MessageProcessingLogID: null])
        assert m.getHeaders()["CamelHttpResponseCode"] == 502
        assert error(m).code == "UPSTREAM_UNREACHABLE"
        assert error(m).message == "Unknown error"
        assert error(m).messageId == "unknown"
        assert logs.logOf(m).properties["ErrorClass"] == "Unknown"
    }

    @Test
    void timeoutsAreGatewayTimeouts() {
        [new java.net.SocketTimeoutException("Read timed out"),
         new java.util.concurrent.TimeoutException("no answer"),
         new IllegalStateException("the call timed out after 60 s"),
         new IllegalStateException("Timeout waiting for connection")].each { cause ->
            def m = failed(cause)
            assert m.getHeaders()["CamelHttpResponseCode"] == 504
            assert error(m).code == "UPSTREAM_TIMEOUT"
            assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "UPSTREAM_TIMEOUT"
        }
    }

    @Test
    void rejectedMethodsAnswer405WithAllow() {
        def m = failed(new IllegalStateException("METHOD: DELETE"), [KEBOOLA_REJECT_REASON: "METHOD_NOT_ALLOWED", KEBOOLA_REJECT_DETAIL: "DELETE"])
        assert m.getHeaders()["CamelHttpResponseCode"] == 405
        assert m.getHeaders()["Allow"] == "GET, HEAD"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "METHOD_NOT_ALLOWED"
        assert error(m).code == "METHOD_NOT_ALLOWED"
        assert error(m).message == "HTTP method DELETE is not supported by this connector. Allowed: GET, HEAD."
        def bare = failed(null, [KEBOOLA_REJECT_REASON: "METHOD_NOT_ALLOWED"])
        assert error(bare).message == "Unknown error"
        assert bare.getHeaders()["Allow"] == "GET, HEAD"
    }

    @Test
    void configurationErrorsAnswer500WithTheDetail() {
        def m = failed(new IllegalStateException("CONFIG: x"),
                       [KEBOOLA_REJECT_REASON: "CONFIG_ERROR", KEBOOLA_REJECT_DETAIL: "S4_HOSTNAME is not configured on this integration flow."])
        assert m.getHeaders()["CamelHttpResponseCode"] == 500
        assert error(m).code == "CONFIG_ERROR"
        assert error(m).message == "S4_HOSTNAME is not configured on this integration flow."
        assert m.getHeaders()["Allow"] == null
        def bare = failed(new IllegalStateException("CONFIG: x"), [KEBOOLA_REJECT_REASON: "CONFIG_ERROR"])
        assert error(bare).message == "CONFIG: x"
    }

    // 1.0.0 behaviour: every NullPointerException reads as "sign-in not prepared"; the
    // connector track narrows this to the credential-alias check (BUILD-BRIEF-v11 §4.1)
    @Test
    void missingCredentialIsReportedAsSignInNotPrepared() {
        [new NullPointerException(),
         new IllegalStateException("No artifact descriptor found for KEBOOLA_S4"),
         new RuntimeException("Could not find credential with alias KEBOOLA_S4")].each { cause ->
            def m = failed(cause)
            assert m.getHeaders()["CamelHttpResponseCode"] == 500
            assert error(m).code == "CONFIG_ERROR"
            assert error(m).message.startsWith("The sign-in to SAP S/4HANA could not be prepared. Check the security material named in S4_CREDENTIAL_ALIAS")
        }
        assert error(failed(new NullPointerException())).message.endsWith("The platform reported: No message")
        assert error(failed(new IllegalStateException("No artifact descriptor found for KEBOOLA_S4"))).message
            .endsWith("The platform reported: No artifact descriptor found for KEBOOLA_S4")
    }

    @Test
    void signInNotPreparedIsRecognisedByClassOrText() {
        assert script.signInNotPrepared("java.lang.NullPointerException", "No message")
        assert script.signInNotPrepared("java.lang.IllegalStateException", "No artifact descriptor found for X")
        assert script.signInNotPrepared("java.lang.RuntimeException", "Could not find credential X")
        assert !script.signInNotPrepared("java.net.ConnectException", "Connection refused")
    }

    // Message cleaning
    @Test
    void exceptionClassPrefixesAndScriptLocationsAreStripped() {
        assert error(failed(new RuntimeException("java.io.IOException: java.net.ConnectException: Connection refused"))).message == "Connection refused"
        assert error(failed(new RuntimeException("Boom @ line 12 in script42.groovy"))).message == "Boom"
        assert error(failed(new RuntimeException("java.io.IOException:   "))).message == "Unknown error"
        assert error(failed(new RuntimeException(""))).message == "No message"
    }

    @Test
    void jsonBodyStaysValidWithAwkwardText() {
        def m = failed(new RuntimeException('a "quoted" \\ back\nslash\ttab\u0001ctl'))
        assert error(m).message == 'a "quoted" \\ back\nslash\ttab ctl'
        assert script.jsonEscape('"') == '\\"'
        assert script.jsonEscape("\\") == "\\\\"
        assert script.jsonEscape("\n\r\t") == "\\n\\r\\t"
        assert script.jsonEscape("\u0002") == " "
        assert script.jsonEscape(null) == ""
    }

    // Monitor
    @Test
    void monitorGetsOutcomeStatusClassAndAttachments() {
        def m = failed(new java.net.ConnectException("Connection refused"), [KEBOOLA_INCOMING_HEADERS: "Accept: application/json"])
        def log = logs.logOf(m)
        assert log.customHeaderProperties["UpstreamOutcome"] == "FAILED"
        assert log.customHeaderProperties["UpstreamStatus"] == "502"
        assert log.properties["UpstreamOutcome"] == "FAILED"
        assert log.properties["UpstreamStatus"] == "502"
        assert log.properties["ErrorClass"] == "java.net.ConnectException"
        assert log.attachments["ErrorDetails"].content == "Connection refused"
        assert log.attachments["ErrorDetails"].mediaType == "text/plain"
        assert log.attachments["IncomingHeaders"].content == "Accept: application/json"
        assert logs.logOf(failed(new RuntimeException("x"))).attachments["IncomingHeaders"] == null
    }

    @Test
    void worksWithoutAMessageLog() {
        logs.setAvailable(false)
        def m = failed(new RuntimeException("x"))
        assert error(m).code == "UPSTREAM_UNREACHABLE"
    }

    @Test
    void integrationKeyHeaderIsClearedOnTheAnswer() {
        def m = message(properties: [CFG_AIR_HEADER_NAME: "X-SAP-AIR"], headers: ["X-SAP-AIR": "key-1"])
        script.processData(m)
        assert m.getHeaders().containsKey("X-SAP-AIR")
        assert m.getHeaders()["X-SAP-AIR"] == null
        def placeholder = message(properties: [CFG_AIR_HEADER_NAME: "{{AIR_HEADER_NAME}}"], headers: ["X-SAP-AIR": "key-1"])
        script.processData(placeholder)
        assert placeholder.getHeaders()["X-SAP-AIR"] == "key-1"
    }

    @Test
    void readCfgTreatsPlaceholdersAsUnset() {
        def m = message(properties: [A: "{{X}}", B: " b ", C: ""])
        assert script.readCfg(m, "A") == null
        assert script.readCfg(m, "B") == "b"
        assert script.readCfg(m, "C") == null
        assert script.readCfg(m, "D") == null
    }
}
