import com.sap.gateway.ip.core.customdev.util.Message

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

static String firstMatch(String text, String pattern) {
    def m = (text =~ pattern)
    return m.find() ? m.group(1) : null
}

static String unescapeJson(String s) {
    def out = new StringBuilder()
    int i = 0
    while (i < s.length()) {
        String c = s.substring(i, i + 1)
        if (c != '\\' || i + 1 >= s.length()) {
            out.append(c)
            i++
            continue
        }
        String n = s.substring(i + 1, i + 2)
        if (n == 'u' && i + 6 <= s.length() && s.substring(i + 2, i + 6) ==~ /[0-9a-fA-F]{4}/) {
            out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16))
            i += 6
            continue
        }
        out.append('nrtbf'.contains(n) ? ' ' : n)
        i += 2
    }
    return out.toString()
}

static String unescapeXml(String s) {
    return s.replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"')
            .replace('&apos;', "'").replace('&amp;', '&')
}

static String oneLine(String s) {
    return s == null ? null : s.replaceAll(/\s+/, ' ').trim()
}

static Map readUpstreamError(String body) {
    String head = body.length() > 8192 ? body.substring(0, 8192) : body
    String probe = head.trim()
    String code = null
    String text = null

    if (probe.startsWith('{')) {
        code = firstMatch(head, '"code"\\s*:\\s*"((?:[^"\\\\]++|\\\\.)*+)')
        text = firstMatch(head, '"message"\\s*:\\s*\\{[^{}]*?"value"\\s*:\\s*"((?:[^"\\\\]++|\\\\.)*+)')
        if (text == null) {
            text = firstMatch(head, '"message"\\s*:\\s*"((?:[^"\\\\]++|\\\\.)*+)')
        }
        code = code == null ? null : unescapeJson(code)
        text = text == null ? null : unescapeJson(text)
    } else if (probe.startsWith('<')) {
        code = firstMatch(head, '<(?:\\w+:)?code[^>]*>([^<]*)</')
        text = firstMatch(head, '<(?:\\w+:)?message[^>]*>([^<]*)</')
        if (text == null) {
            text = firstMatch(head, '(?is)<title[^>]*>(.*?)</title>')
        }
        if (text == null) {
            text = head.replaceAll('(?is)<(style|script)[^>]*>.*?</\\1>', ' ')
                       .replaceAll('<[^>]*>', ' ')
        }
        code = code == null ? null : unescapeXml(code)
        text = text == null ? null : unescapeXml(text)
    } else {
        text = head
    }
    return [code: oneLine(code), text: oneLine(text)]
}

def Message processData(Message message) {
    def headers = message.getHeaders()
    def messageLog = messageLogFactory.getMessageLog(message)

    // Upstream status
    int status = 0
    try {
        def raw = headers.get("CamelHttpResponseCode")
        status = raw != null ? Integer.parseInt(raw.toString()) : 0
    } catch (NumberFormatException ignored) {
        status = 0
    }

    boolean failed = status == 0 || status >= 400
    String customStatus = failed ? "UPSTREAM_FAILED" : "OK"
    String upstreamOutcome = failed ? "FAILED" : "OK"

    message.setProperty("SAP_MessageProcessingLogCustomStatus", customStatus)

    // Monitor properties
    if (messageLog != null) {
        def statusText = status > 0 ? status.toString() : "unknown"
        messageLog.addCustomHeaderProperty("UpstreamStatus", statusText)
        messageLog.addCustomHeaderProperty("UpstreamOutcome", upstreamOutcome)
        messageLog.setStringProperty("UpstreamStatus", statusText)
        messageLog.setStringProperty("UpstreamOutcome", upstreamOutcome)

        if (failed) {
            // SAP error
            try {
                def body = message.getBody(String) ?: ""
                def error = readUpstreamError(body)
                if (error.code) {
                    messageLog.addCustomHeaderProperty("UpstreamErrorCode", error.code.take(200))
                    messageLog.setStringProperty("UpstreamErrorCode", error.code.take(200))
                }
                if (error.text) {
                    messageLog.addCustomHeaderProperty("UpstreamError", error.text.take(200))
                    messageLog.setStringProperty("UpstreamError", error.text.take(1000))
                }
            } catch (Throwable t) {
                messageLog.setStringProperty("UpstreamError",
                    "(the error answer could not be read: ${t.getClass().getSimpleName()})")
            }
            // Incoming headers
            def incoming = message.getProperty("KEBOOLA_INCOMING_HEADERS")?.toString()
            if (incoming) {
                messageLog.setStringProperty("IncomingHeaders", incoming.replace("\n", " | "))
            }
        }
    }

    // SAP integration key
    def airHeader = readCfg(message, "CFG_AIR_HEADER_NAME")
    if (airHeader) {
        message.setHeader(airHeader, null)
    }

    return message
}
