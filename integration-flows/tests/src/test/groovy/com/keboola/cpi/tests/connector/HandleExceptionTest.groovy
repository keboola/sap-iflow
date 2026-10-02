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

    // A Throwable thrown from a named place, the way the platform's own frames look.
    static Throwable thrownAt(Throwable cause, String className, String method) {
        cause.setStackTrace([new StackTraceElement(className, method, className + ".java", 42)] as StackTraceElement[])
        return cause
    }

    // A NullPointerException is a sign-in fault only when the platform threw it while
    // preparing the sign-in (the class that threw it names the credential handling);
    // the three credential texts count whatever the class.
    @Test
    void missingCredentialIsReportedAsSignInNotPrepared() {
        [thrownAt(new NullPointerException(), "com.sap.it.rt.adapter.http.api.auth.OAuth2ClientCredentialsHandler", "getToken"),
         new IllegalStateException("No artifact descriptor found for KEBOOLA_S4"),
         new RuntimeException("Could not find credential with alias KEBOOLA_S4"),
         new RuntimeException("No credentials for KEBOOLA_S4 available. Deploy suitable credentials or adapt credential name in integration flow.")].each { cause ->
            def m = failed(cause)
            assert m.getHeaders()["CamelHttpResponseCode"] == 500
            assert error(m).code == "CONFIG_ERROR"
            assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "CONFIG_ERROR"
            assert error(m).message.startsWith("The sign-in to SAP S/4HANA could not be prepared. Check the security material named in S4_CREDENTIAL_ALIAS")
            assert error(m).message.contains("S4_AUTH_METHOD")
        }
        assert error(failed(thrownAt(new NullPointerException(), "com.sap.esb.security.CredentialStore", "read"))).message
            .endsWith("The platform reported: No message")
        assert error(failed(new IllegalStateException("No artifact descriptor found for KEBOOLA_S4"))).message
            .endsWith("The platform reported: No artifact descriptor found for KEBOOLA_S4")
    }

    @Test
    void aNullPointerElsewhereIsTheConnectorsOwnError() {
        def m = failed(thrownAt(new NullPointerException(), "com.sap.it.rt.adapter.http.core.HttpProducer", "process"))
        assert m.getHeaders()["CamelHttpResponseCode"] == 500
        assert error(m).code == "CONNECTOR_ERROR"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "CONNECTOR_ERROR"
        assert error(m).message == "The connector failed inside the platform at com.sap.it.rt.adapter.http.core.HttpProducer.process. The platform reported: No message"
        assert logs.logOf(m).properties["ErrorLocation"] == "com.sap.it.rt.adapter.http.core.HttpProducer.process"
        assert logs.logOf(m).customHeaderProperties["ErrorLocation"] == "com.sap.it.rt.adapter.http.core.HttpProducer.process"
        assert logs.logOf(m).properties["ErrorClass"] == "java.lang.NullPointerException"
        // a sign-in fault is searchable by its place too; a rejected request is not (its place is the script)
        def signIn = failed(thrownAt(new NullPointerException(), "com.sap.esb.security.CredentialStore", "read"))
        assert logs.logOf(signIn).customHeaderProperties["ErrorLocation"] == "com.sap.esb.security.CredentialStore.read"
        def rejected = failed(thrownAt(new IllegalStateException("CONFIG: x"), "Script1", "processData"), [KEBOOLA_REJECT_REASON: "CONFIG_ERROR"])
        assert logs.logOf(rejected).customHeaderProperties["ErrorLocation"] == null
        assert logs.logOf(rejected).properties["ErrorLocation"] == "Script1.processData"
        // no frames at all: still the connector's, not a sign-in fault
        def bare = new NullPointerException("x was null")
        bare.setStackTrace([] as StackTraceElement[])
        def b = failed(bare)
        assert error(b).code == "CONNECTOR_ERROR"
        assert error(b).message == "The connector failed inside the platform at an unknown place. The platform reported: x was null"
        assert logs.logOf(b).properties["ErrorLocation"] == null
    }

    @Test
    void signInNotPreparedIsRecognisedByLocationOrText() {
        assert script.signInNotPrepared("java.lang.NullPointerException", "No message", "com.sap.it.rt.adapter.http.api.auth.Basic.apply")
        assert script.signInNotPrepared("java.lang.NullPointerException", "No message", "com.sap.esb.securestore.Store.get")
        assert !script.signInNotPrepared("java.lang.NullPointerException", "No message", "com.sap.it.rt.adapter.http.core.HttpProducer.process")
        assert !script.signInNotPrepared("java.lang.NullPointerException", "No message", "")
        assert !script.signInNotPrepared("java.lang.NullPointerException", "No message", null)
        assert script.signInNotPrepared("java.lang.IllegalStateException", "No artifact descriptor found for X", "anywhere.Here.now")
        assert script.signInNotPrepared("java.lang.RuntimeException", "Could not find credential X", "")
        assert script.signInNotPrepared("java.lang.RuntimeException", "No credentials for X available. Deploy suitable credentials or adapt credential name in integration flow.", "")
        assert !script.signInNotPrepared("java.net.ConnectException", "Connection refused", "com.sap.it.rt.adapter.http.api.auth.Basic.apply")
        assert script.signInLocation("com.sap.it.rt.adapter.http.api.auth.OAuth2Handler.token")
        assert script.signInLocation("com.sap.it.rt.security.CredentialResolver.resolve")
        assert !script.signInLocation("org.apache.camel.processor.Pipeline.process")
    }

    @Test
    void pathsOutsideThePrefixesAnswer403() {
        def m = failed(new IllegalStateException("PATH: /sap/bc/ping is outside CONNECTOR_PATH_PREFIXES."),
                       [KEBOOLA_REJECT_REASON: "PATH_NOT_ALLOWED", KEBOOLA_REJECT_DETAIL: "/sap/bc/ping",
                        CFG_PATH_PREFIXES: "/sap/opu/odata/,/sap/opu/odata4/"])
        assert m.getHeaders()["CamelHttpResponseCode"] == 403
        assert m.getHeaders()["Allow"] == null
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "PATH_NOT_ALLOWED"
        def e = error(m)
        assert e.code == "PATH_NOT_ALLOWED"
        assert e.message == "The path /sap/bc/ping is outside the paths this connector forwards to SAP S/4HANA. CONNECTOR_PATH_PREFIXES allows /sap/opu/odata/,/sap/opu/odata4/; add a prefix there, or leave it empty to forward every path."
        assert e.messageId == "AGq7-run"
        assert logs.logOf(m).customHeaderProperties["UpstreamStatus"] == "403"
        assert logs.logOf(m).customHeaderProperties["UpstreamOutcome"] == "FAILED"
        // the connector never reached SAP, so no upstream error is invented
        def bare = failed(null, [KEBOOLA_REJECT_REASON: "PATH_NOT_ALLOWED"])
        assert error(bare).message.startsWith("The path the requested path is outside")
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
