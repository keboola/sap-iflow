package com.keboola.cpi.tests.catalogue

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory
import groovy.json.JsonSlurper
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// handleCatalogException.groovy: the catalogue's error answer — the prefixes of the build script,
// the platform's failures, the custom statuses, messageId in every body. The same script runs in
// the main process and inside both local processes.
class HandleCatalogExceptionTest {

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.CATALOGUE, "handleCatalogException", logs)
    }

    Message failed(Throwable cause, Map properties = [:]) {
        def m = message(properties: [CATALOG_RUN_ID: "AGq9-run", CATALOG_NOTES: ["arrangements: page 1"]] + properties,
                        headers: ["X-Air-Key": "k"])
        if (cause != null) { m.setProperty("CamelExceptionCaught", cause) }
        script.processData(m)
        return m
    }

    static Map error(Message m) {
        return new JsonSlurper().parseText(m.getBody().toString()).error
    }

    @Test
    void theBuildScriptsPrefixesBecomeConfigAndUpstreamErrors() {
        def config = failed(new IllegalStateException("CONFIG: S4_HOSTNAME is not the API address of the system: the arrangement API answered HTTP 404"))
        assert config.getHeaders()["CamelHttpResponseCode"] == 500
        assert error(config).code == "CONFIG_ERROR"
        assert error(config).message.startsWith("S4_HOSTNAME is not the API address")
        assert error(config).messageId == "AGq9-run"
        assert config.getProperty("SAP_MessageProcessingLogCustomStatus") == "CONFIG_ERROR"
        def upstream = failed(new IllegalStateException("UPSTREAM: SAP refused the sign-in (HTTP 401)."))
        assert upstream.getHeaders()["CamelHttpResponseCode"] == 502
        assert error(upstream).code == "UPSTREAM_ERROR"
        assert upstream.getProperty("SAP_MessageProcessingLogCustomStatus") == "UPSTREAM_ERROR"
    }

    @Test
    void anUnreachableHostAndATimeoutAreToldApart() {
        def dns = failed(new java.net.UnknownHostException("no-such-host.invalid: Name or service not known"))
        assert dns.getHeaders()["CamelHttpResponseCode"] == 502
        assert error(dns).code == "UPSTREAM_UNREACHABLE"
        assert dns.getProperty("SAP_MessageProcessingLogCustomStatus") == "UPSTREAM_UNREACHABLE"
        def timeout = failed(new java.util.concurrent.TimeoutException("Request timeout to host after 10000 ms"))
        assert timeout.getHeaders()["CamelHttpResponseCode"] == 504
        assert error(timeout).code == "UPSTREAM_TIMEOUT"
        def deadline = failed(new IllegalStateException("Reading the communication arrangements of https://s4 timed out: 7 calls did not finish within the 35-second deadline of one read."))
        assert deadline.getHeaders()["CamelHttpResponseCode"] == 504
        assert error(deadline).code == "UPSTREAM_TIMEOUT"
        assert deadline.getProperty("SAP_MessageProcessingLogCustomStatus") == "UPSTREAM_TIMEOUT"
    }

    @Test
    void aRefusedMethodIs405WithAllow() {
        def m = failed(new IllegalStateException("HTTP method POST is not supported by this endpoint. Allowed: GET, HEAD."),
                       [KEBOOLA_REJECT_REASON: "METHOD_NOT_ALLOWED", KEBOOLA_REJECT_DETAIL: "POST"])
        assert m.getHeaders()["CamelHttpResponseCode"] == 405
        assert m.getHeaders()["Allow"] == "GET, HEAD"
        assert error(m).code == "METHOD_NOT_ALLOWED"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "METHOD_NOT_ALLOWED"
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
    void aMissingSecurityMaterialNamesTheSignInParameters() {
        [thrownAt(new NullPointerException(), "com.sap.it.rt.adapter.odata.oauth.cache.provider.OauthTokenProviderFactory", "getOauthCacheHandler"),
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
        def signIn = failed(thrownAt(new NullPointerException(), "com.sap.esb.security.CredentialStore", "read"))
        assert error(signIn).message.endsWith("The platform reported: No message")
        assert logs.logOf(signIn).customHeaderProperties["ErrorLocation"] == "com.sap.esb.security.CredentialStore.read"
        assert logs.logOf(signIn).properties["ErrorLocation"] == "com.sap.esb.security.CredentialStore.read"
    }

    @Test
    void aNullPointerElsewhereIsTheCataloguesOwnError() {
        def m = failed(thrownAt(new NullPointerException(), "com.sap.it.rt.adapter.http.core.HttpProducer", "process"))
        assert m.getHeaders()["CamelHttpResponseCode"] == 500
        assert error(m).code == "CATALOG_ERROR"
        assert m.getProperty("SAP_MessageProcessingLogCustomStatus") == "CATALOG_ERROR"
        assert error(m).message == "The catalogue failed inside the platform at com.sap.it.rt.adapter.http.core.HttpProducer.process. The platform reported: No message"
        assert logs.logOf(m).properties["ErrorLocation"] == "com.sap.it.rt.adapter.http.core.HttpProducer.process"
        assert logs.logOf(m).customHeaderProperties["ErrorLocation"] == "com.sap.it.rt.adapter.http.core.HttpProducer.process"
        assert logs.logOf(m).properties["ErrorClass"] == "java.lang.NullPointerException"
        // a build-script refusal keeps its own code and place; the place is not a searchable property
        def config = failed(thrownAt(new IllegalStateException("CONFIG: S4_HOSTNAME is empty"), "Script1", "processData"))
        assert error(config).code == "CONFIG_ERROR"
        assert error(config).message == "S4_HOSTNAME is empty"
        assert logs.logOf(config).customHeaderProperties["ErrorLocation"] == null
        assert logs.logOf(config).properties["ErrorLocation"] == "Script1.processData"
        // no frames at all: still the catalogue's, not a sign-in fault
        def bare = new NullPointerException("x was null")
        bare.setStackTrace([] as StackTraceElement[])
        def b = failed(bare)
        assert error(b).code == "CATALOG_ERROR"
        assert error(b).message == "The catalogue failed inside the platform at an unknown place. The platform reported: x was null"
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
        assert script.signInLocation("com.sap.it.rt.adapter.odata.oauth.cache.provider.OauthTokenProviderFactory.getOauthCacheHandler")
        assert !script.signInLocation("org.apache.camel.processor.Pipeline.process")
    }

    @Test
    void theMonitorGetsTheErrorAndTheNotesAndTheKeyIsCleared() {
        def m = failed(new IllegalStateException("UPSTREAM: SAP answered the catalogue requests with a server error"),
                       [CFG_AIR_HEADER_NAME: "X-Air-Key", KEBOOLA_INCOMING_HEADERS: "Accept: application/json"])
        def log = logs.logOf(m)
        assert log.customHeaderProperties["UpstreamOutcome"] == "FAILED"
        assert log.customHeaderProperties["UpstreamStatus"] == "502"
        assert log.customHeaderProperties["CatalogSource"] == "error"
        assert log.attachments["ErrorDetails"].content.startsWith("SAP answered")
        assert log.attachments["IncomingHeaders"].content == "Accept: application/json"
        assert log.properties["CatalogDiagnostics"] == "arrangements: page 1"
        assert m.getHeaders()["X-Air-Key"] == null
        assert m.getHeaders()["Content-Type"] == "application/json"
    }
}
