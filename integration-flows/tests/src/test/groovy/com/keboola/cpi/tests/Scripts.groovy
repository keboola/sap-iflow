package com.keboola.cpi.tests

import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.msglog.MessageLogFactory

// Loads a flow script the way Cloud Integration does: GroovyShell, a Binding that holds
// messageLogFactory, the file taken from <scripts.root>/<ArtifactId>/src/main/resources/script/.
class Scripts {

    static final String CONNECTOR = "KeboolaODataConnector"
    static final String CATALOGUE = "QueryAvailableServicesFromSAPS4HANA"
    static final String DELIVERY = "DeliverBusinessDataFromSAPS4HANAToKeboola"

    static File root() {
        String configured = System.getProperty("scripts.root", "..")
        File dir = new File(configured)
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("basedir", System.getProperty("user.dir")), configured)
        }
        return dir.canonicalFile
    }

    static File file(String artifactId, String script) {
        return new File(root(), artifactId + "/src/main/resources/script/" + script + ".groovy")
    }

    static Script load(String artifactId, String script, MessageLogFactory logs = new MessageLogFactory()) {
        File source = file(artifactId, script)
        assert source.isFile() : "script not found: " + source + " (set -Dscripts.root to the folder that holds " + artifactId + ")"
        Binding binding = new Binding()
        binding.setVariable("messageLogFactory", logs)
        return new GroovyShell(binding).parse(source)
    }

    static Message message(Map args = [:]) {
        Message message = new Message()
        (args.properties ?: [:]).each { key, value -> message.setProperty(key.toString(), value) }
        (args.headers ?: [:]).each { key, value -> message.setHeader(key.toString(), value) }
        if (args.containsKey("body")) { message.setBody(args.body) }
        return message
    }

    static Throwable failure(Closure work) {
        try {
            work.call()
        } catch (Throwable caught) {
            return caught
        }
        throw new AssertionError("expected an exception, the call returned normally")
    }

    // A Throwable thrown from a named place, the way the platform's own frames look.
    static Throwable thrownAt(Throwable cause, String className, String method) {
        cause.setStackTrace([new StackTraceElement(className, method, className + ".java", 42)] as StackTraceElement[])
        return cause
    }
}
