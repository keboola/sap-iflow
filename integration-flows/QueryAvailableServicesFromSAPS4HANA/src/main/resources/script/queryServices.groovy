import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.asdk.datastore.DataBean
import com.sap.it.api.asdk.datastore.DataConfig
import com.sap.it.api.asdk.datastore.DataStoreService
import com.sap.it.api.asdk.runtime.Factory
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

static Map httpCall(String urlStr, String auth, String method, int connectMs, int readMs,
                    Map extra = null) {
    HttpURLConnection c = null
    try {
        c = (HttpURLConnection) new URL(urlStr).openConnection()
        c.setRequestMethod(method)
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("Accept", "application/json")
        if (extra != null) { extra.each { k, v -> c.setRequestProperty((String) k, (String) v) } }
        c.setConnectTimeout(connectMs)
        c.setReadTimeout(readMs)
        c.setInstanceFollowRedirects(false)
        int code = c.getResponseCode()
        String body = ""
        if (method != "HEAD") {
            if (code >= 200 && code < 300) {
                body = c.getInputStream().getText("UTF-8")
            } else {
                try { body = c.getErrorStream()?.getText("UTF-8") ?: "" } catch (Exception ignored) { }
            }
        }
        return [code: code, body: body]
    } catch (java.net.SocketTimeoutException te) {
        return [code: -2, body: "timeout: " + (te.getMessage() ?: "")]
    } catch (Exception e) {
        return [code: -1, body: (e.getClass().getSimpleName() + ": " + (e.getMessage() ?: ""))]
    } finally {
        if (c != null) { c.disconnect() }
    }
}

static Map retryingCall(String urlStr, String auth, String method, int connectMs, int readMs,
                        Map extra, int maxAttempts, long initialDelayMs, double multiplier,
                        List notes) {
    Map last = null
    for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
        last = httpCall(urlStr, auth, method, connectMs, readMs, extra)
        int code = (int) last.code
        boolean transientFailure = code <= 0 || code == 429 || code >= 500
        if (!transientFailure) { return last }
        if (attempt >= maxAttempts) {
            notes << ("giving up after " + attempt + " attempt(s) on " + code + " for " + urlStr)
            return last
        }
        long delay = (long) (initialDelayMs * Math.pow(multiplier, (double) (attempt - 1)))
        notes << ("attempt " + attempt + " got " + code + "; retrying in " + delay + "ms")
        try { TimeUnit.MILLISECONDS.sleep(delay) } catch (InterruptedException e) { return last }
    }
    return last
}

static String addQuery(String url, String qsExtra) {
    if (!qsExtra) { return url }
    def frag = qsExtra.startsWith("&") ? qsExtra.substring(1) : qsExtra
    return url + (url.contains("?") ? "&" : "?") + frag
}

static List fetchAllV4(String base, String firstUrl, String auth, int timeoutMs, List notes,
                       Map extra = null, int maxAttempts = 1, long initialDelayMs = 500L,
                       double multiplier = 2.0d, String qsExtra = "", Map failure = null) {
    def out = []
    String origin = base.replaceFirst(/(?i)^(https?:\/\/[^\/]+).*$/, '$1')
    String url = firstUrl
    int guard = 0
    while (url != null && guard < 50) {
        def r = retryingCall(addQuery(url, qsExtra), auth, "GET", 5000, timeoutMs, extra,
                             maxAttempts, initialDelayMs, multiplier, notes)
        if (r.code != 200) {
            notes << ("arrangements: GET " + url + " -> " + r.code)
            if (failure != null) { failure["code"] = r.code }
            return null
        }
        def j = null
        try { j = parseJson((String) r.body) } catch (Exception ignored) { j = null }
        if (!(j instanceof Map)) {
            throw new IllegalStateException("UPSTREAM: " + base + " answered HTTP 200 but not with JSON. " +
                "S4_HOSTNAME probably names a sign-in page or a web front end, not the API " +
                "address of the system.")
        }
        def page = j["value"]
        if (page instanceof List) { out.addAll(page.findAll { it instanceof Map }) }
        def next = j["@odata.nextLink"]
        if (next) {
            String link = next.toString()
            if (link ==~ /(?i)^https?:\/\/.*/) {
                url = origin + link.replaceFirst(/(?i)^https?:\/\/[^\/?#]*/, "")
            } else {
                url = base + "/" + link
            }
        } else {
            url = null
        }
        guard++
    }
    return out
}

static String stripServiceSuffix(String id, String proto) {
    def patterns = [
        "_\\d{4}_" + proto + '$',
        "_" + proto + '$',
        "_\\d{4}_[A-Z]*" + '$',
        "_\\d{4}" + '$',
        "_+" + '$',
    ]
    for (p in patterns) {
        def s2 = id.replaceAll(p, "")
        if (s2 != id) { return s2 }
    }
    return id
}

static String squash(String s) {
    return s.toUpperCase().replaceAll("[^A-Z0-9]", "")
}

static Object parseJson(Object source) {
    if (source instanceof Reader) { return new JsonSlurper().parse((Reader) source) }
    String text = source?.toString()
    if (!text) { throw new IllegalArgumentException("The JSON input text should neither be null nor empty.") }
    return new JsonSlurper().parse(new StringReader(text))
}

static List entitySetsOf(String body) {
    try {
        def j = parseJson(body)
        if (j instanceof Map && j["d"] instanceof Map && j["d"]["EntitySets"] instanceof List) {
            return j["d"]["EntitySets"]
        }
        if (j instanceof Map && j["value"] instanceof List) {
            return j["value"].findAll { it instanceof Map && (it["kind"] == "EntitySet" || it["kind"] == null) }
                             .collect { it["name"] }.findAll { it }
        }
    } catch (Exception ignored) { }
    return []
}

static Map probeOne(String host, String auth, Map cand, boolean knockData, int connectMs, int readMs,
                    Map extra = null, String qsExtra = "") {
    if (!knockData) {
        def doc = httpCall(host + cand.path + "/?%24format=json" + qsExtra, auth, "GET", connectMs, readMs, extra)
        return [ok: doc.code == 200, code: doc.code, stage: "doc"]
    }
    def sets = []
    if (cand.set) { sets << cand.set }
    if (cand.set2) { sets << cand.set2 }
    boolean prebaked = !sets.isEmpty()
    if (!prebaked) {
        def doc = httpCall(host + cand.path + "/?%24format=json" + qsExtra, auth, "GET", connectMs, readMs, extra)
        if (doc.code != 200) {
            return [ok: false, code: doc.code, stage: "doc"]
        }
        sets = entitySetsOf((String) doc.body)
        if (!sets) {
            return [ok: false, code: 200, stage: "no-entity-sets"]
        }
    }
    def knock = httpCall(host + cand.path + "/" + sets[0] + "?%24top=1" + qsExtra, auth, "GET", connectMs, readMs, extra)
    if (knock.code == 200) {
        return [ok: true, code: 200, stage: prebaked ? "data-1knock" : "data"]
    }
    if (knock.code == 401 || knock.code == 403) {
        return [ok: false, code: knock.code, stage: "data"]
    }
    if (sets.size() > 1) {
        def retry = httpCall(host + cand.path + "/" + sets[1] + "?%24top=1" + qsExtra, auth, "GET", connectMs, readMs, extra)
        if (retry.code == 200) {
            return [ok: true, code: 200, stage: "data-2nd"]
        }
        if (retry.code == 401 || retry.code == 403) {
            return [ok: false, code: retry.code, stage: "data"]
        }
    }
    if (prebaked) {
        def doc = httpCall(host + cand.path + "/?%24format=json" + qsExtra, auth, "GET", connectMs, readMs, extra)
        if (doc.code != 200) {
            return [ok: false, code: doc.code, stage: "doc"]
        }
        def live = entitySetsOf((String) doc.body)
        if (live && !(live[0] in sets)) {
            def k2 = httpCall(host + cand.path + "/" + live[0] + "?%24top=1" + qsExtra, auth, "GET", connectMs, readMs, extra)
            if (k2.code == 200) {
                return [ok: true, code: 200, stage: "data-live"]
            }
            return [ok: false, code: k2.code, stage: "data"]
        }
    }
    return [ok: false, code: knock.code, stage: "data"]
}

def Message processData(Message message) {
    // Rejected requests
    def rejectReason = message.getProperty("KEBOOLA_REJECT_REASON")?.toString()
    if (rejectReason) {
        def detail = message.getProperty("KEBOOLA_REJECT_DETAIL")?.toString() ?: "unknown"
        throw new IllegalStateException(
            "HTTP method ${detail} is not supported by this endpoint. Allowed: GET, HEAD.")
    }

    def messageLog = messageLogFactory.getMessageLog(message)
    def messageId = message.getProperty("CATALOG_RUN_ID")?.toString()
        ?: message.getProperty("SAP_MessageProcessingLogID")?.toString() ?: "unknown"
    long t0 = java.time.Instant.now().toEpochMilli()

    // Configuration
    def host = readCfg(message, "CFG_S4_HOSTNAME")
    def alias = readCfg(message, "CFG_CREDENTIAL_ALIAS")
    if (!host) {
        throw new IllegalStateException("CONFIG: S4_HOSTNAME is not configured on this integration flow.")
    }
    if (!alias) {
        throw new IllegalStateException("CONFIG: S4_CREDENTIAL_ALIAS is not configured on this integration flow.")
    }
    boolean throughCloudConnector =
        (readCfg(message, "CFG_PROXY_TYPE") ?: "").toLowerCase() == "sapcc"
    boolean tunnelledHttp = throughCloudConnector && host.toLowerCase().startsWith("http://")
    if (!tunnelledHttp && !host.toLowerCase().startsWith("https://")) {
        if (host.toLowerCase().startsWith("http://")) {
            throw new IllegalStateException(
                "CONFIG: S4_HOSTNAME must start with https:// - credentials and business " +
                "data are never sent over an unencrypted connection.")
        }
        host = "https://" + host
    }
    host = host.replaceAll("/+\$", "")

    def airKey = readCfg(message, "CFG_AIR_KEY")
    def airHeaderName = readCfg(message, "CFG_AIR_HEADER_NAME")
    def airHeaders = (airKey && airHeaderName) ? [(airHeaderName): airKey] : null
    String cfgTimeout = readCfg(message, "CFG_TIMEOUT_MS")
    int timeoutMs = (cfgTimeout == null) ? 20000 : toInt(cfgTimeout, 0)
    if (timeoutMs < 1) {
        throw new IllegalStateException("CONFIG: HTTP_TIMEOUT_MS must be a whole number of milliseconds " +
                                        "greater than 0; it is '" + cfgTimeout + "'.")
    }
    String cfgTtl = readCfg(message, "CFG_CACHE_TTL_SECONDS")
    long ttlSec = (cfgTtl == null) ? 3600L : toLong(cfgTtl, -1L)
    if (ttlSec < 0) {
        throw new IllegalStateException("CONFIG: CATALOG_CACHE_TTL_SECONDS must be a whole number of seconds, " +
                                        "0 or more; it is '" + cfgTtl + "'.")
    }
    int retryAttempts = 3
    long retryDelayMs = 500L
    double retryMultiplier = 2.0d

    // Support switches
    def qs = parseQuery(message.getProperty("CATALOG_QUERY")?.toString()
                        ?: message.getHeaders().get("CamelHttpQuery")?.toString())
    def forcedMode = qs["mode"]
    boolean nocache = qs["nocache"] == "1" || qs["nocache"] == "true"
    boolean filterByUser = qs["filter"] != "none"
    def cfgView = (readCfg(message, "CFG_CATALOG_VIEW") ?: "interfaces").trim().toLowerCase()
    if (!(cfgView in ["interfaces", "extended", "all"])) {
        throw new IllegalStateException("CONFIG: CATALOG_VIEW must be one of interfaces, extended, all - got '" +
                                        cfgView + "'.")
    }
    def cfgCheck = (readCfg(message, "CFG_CHECK_AUTHORIZATION") ?: "true").trim().toLowerCase()
    if (!(cfgCheck in ["true", "false"])) {
        throw new IllegalStateException("CONFIG: CATALOG_CHECK_AUTHORIZATION must be true or false - got '" +
                                        cfgCheck + "'.")
    }
    String view = viewOf(qs["include"], cfgView)
    String odata = odataOf(qs["odata"])
    boolean checkAuth = filterByUser && cfgCheck == "true"
    message.setProperty("CATALOG_DEBUG", (qs["debug"] == "1" || qs["debug"] == "true") ? "true" : "false")
    def sapClient = readCfg(message, "CFG_SAP_CLIENT")
    String qsExtra = sapClient ? ("&sap-client=" + java.net.URLEncoder.encode(sapClient, "UTF-8")) : ""

    // Adapter route
    if (message.getProperty("CATALOG_ROUTE")?.toString() == "adapter") {
        def notes = []
        def idx = indexCandidates(loadCandidates())
        def rows = []
        int v2code = toInt(message.getProperty("GW_V2_STATUS")?.toString(), 0)
        int v4code = toInt(message.getHeaders().get("CamelHttpResponseCode")?.toString(), 0)
        if (v2code == 200) {
            def v2rows = gatewayRowsOf(message.getProperty("GW_V2_BODY")?.toString() ?: "")
            if (v2rows != null) {
                rows.addAll(v2rows)
                notes << ("gateway v2 (via adapter): " + v2rows.size() + " catalogue rows")
            } else {
                notes << "gateway v2 (via adapter): unparseable response, ignored"
            }
        } else {
            notes << ("gateway v2 (via adapter): HTTP " + v2code)
        }
        if (v4code == 200) {
            def entries = v4CatalogEntries(message.getBody(java.io.Reader), idx.v4bySquash, idx.v4byGroup, notes)
            entries.each { e ->
                def desc = e.needsVerify ? (e.title + " (address derived from the V4 catalogue, unverified)") : e.title
                rows << [ID: e.name, Title: e.name, Description: desc,
                         ServiceUrl: host + e.path, MetadataUrl: host + e.path + "/\$metadata"]
            }
            notes << ("gateway v4 (via adapter): " + entries.size() + " services, " +
                      entries.count { it.needsVerify } + " with a derived address")
        } else {
            notes << ("gateway v4 (via adapter): HTTP " + v4code)
        }
        if (!rows) {
            throw new IllegalStateException("UPSTREAM: Gateway catalogue is not readable on " + host +
                                            " through the configured connection (V2 HTTP " + v2code +
                                            ", V4 HTTP " + v4code + ")")
        }
        message.setHeader("CamelHttpResponseCode", null)
        respond(message, messageLog, messageId, JsonOutput.toJson([d: [results: rows]]),
                "gateway-cc", notes, toLong(message.getProperty("CATALOG_T0")?.toString(), t0), view, odata)
        return message
    }

    // Credentials
    def secureStore = ITApiFactory.getService(SecureStoreService.class, null)
    if (secureStore == null) {
        throw new IllegalStateException("CONFIG: secure store service is not available on this runtime.")
    }
    def cred = null
    try {
        cred = secureStore.getUserCredential(alias)
    } catch (Exception e) {
        throw new IllegalStateException("CONFIG: the security material named '" + alias + "' (S4_CREDENTIAL_ALIAS) could not be read: " + e.getMessage())
    }
    if (cred == null) {
        throw new IllegalStateException("CONFIG: no security material named '" + alias +
                                        "' is deployed in this tenant (S4_CREDENTIAL_ALIAS)")
    }
    String userName = cred.getUsername()
    String auth = "Basic " + (userName + ":" + new String(cred.getPassword())).getBytes("UTF-8").encodeBase64().toString()

    def notes = []

    // Cache lookup
    final String STORE = "KeboolaCatalogCache"
    String cacheId = "catalog:" + host.replaceAll(/^https?:\/\//, "").replaceAll(/[^A-Za-z0-9._-]/, "_") +
                     ":" + userName + ":" + (filterByUser ? "user" : "all") +
                     (sapClient ? ":" + sapClient.replaceAll(/[^A-Za-z0-9]/, "_") : "") +
                     (cfgCheck == "true" ? "" : ":unchecked") +
                     ((checkAuth && view == "extended") ? ":extended" : "")
    DataStoreService dataStore = null
    if (ttlSec > 0) {
        try { dataStore = new Factory(DataStoreService.class).getService() } catch (Exception ignored) { }
    }
    if (dataStore != null && !nocache && !forcedMode) {
        try {
            def bean = dataStore.get(STORE, cacheId)
            if (bean != null) {
                def env = parseJson(new String(bean.getDataAsArray(), "UTF-8"))
                long age = (java.time.Instant.now().toEpochMilli() - ((Number) env["cachedAt"]).longValue()) / 1000L
                if (age >= 0 && age < ttlSec && env["payload"]) {
                    respond(message, messageLog, messageId, (String) env["payload"],
                            "cache(" + env["source"] + ", " + age + "s old)", notes, t0, view, odata)
                    return message
                }
            }
        } catch (Exception e) {
            notes << ("cache read skipped: " + e.getMessage())
        }
    }

    // Shipped service list
    def candidates = loadCandidates()
    def idx = indexCandidates(candidates)
    def v2byName = idx.v2byName
    def v2bySquash = idx.v2bySquash
    def v4byGroup = idx.v4byGroup
    def v4bySquash = idx.v4bySquash

    // Communication arrangements
    String payload = null
    String source = null
    if (!forcedMode || forcedMode == "arrangements") {
        def base = host + "/sap/opu/odata4/sap/aps_com_api_ca_read/srvd_a2x/sap/communicationarrangement/0001"
        def failure = [:]
        def arrs = fetchAllV4(base, base + "/CommunicationArrangements", auth, timeoutMs, notes,
                              airHeaders, retryAttempts, retryDelayMs, retryMultiplier, qsExtra, failure)
        if (arrs == null && failure["code"] == 401) {
            throw new IllegalStateException("UPSTREAM: SAP refused the sign-in (HTTP 401). Check the user and " +
                "password of the security material named in S4_CREDENTIAL_ALIAS, and that the user is " +
                "not locked in SAP.")
        }
        def inbound = (arrs != null) ? fetchAllV4(base, base + "/CommArrangementsInboundServ", auth,
                              timeoutMs, notes, airHeaders, retryAttempts, retryDelayMs, retryMultiplier, qsExtra) : null
        def users = (inbound != null) ? fetchAllV4(base, base + "/CommArrangementsInboundUsers", auth,
                              timeoutMs, notes, airHeaders, retryAttempts, retryDelayMs, retryMultiplier, qsExtra) : null
        if (users != null) {
            def entries = resolveArrangements(arrs, inbound, users, userName, filterByUser,
                                              v2byName, v2bySquash, v4byGroup, notes)
            def toVerify = entries.findAll { it.needsVerify }
            if (toVerify.size() > 0 && toVerify.size() <= 60) {
                def verified = runParallel(toVerify, 16, 45) { cand ->
                    probeOne(host, auth, cand, true, 3000, 8000, airHeaders, qsExtra)
                }
                def keep = [] as Set
                verified.eachWithIndex { r, i ->
                    if (r != null && r.ok) { keep << toVerify[i].path }
                    else { notes << ("verify dropped " + toVerify[i].name + " (" + (r == null ? "no result" : r.code + "@" + r.stage) + ")") }
                }
                entries = entries.findAll { !it.needsVerify || keep.contains(it.path) }
            }
            payload = buildPayload(host, entries)
            source = "arrangements"
            notes << ("arrangements: " + entries.size() + " services for user " + userName +
                      (filterByUser ? "" : " (unfiltered)"))
        } else if (forcedMode == "arrangements") {
            throw new IllegalStateException("UPSTREAM: The communication arrangements (SAP_COM_0A07) are not " +
                                            "readable on " + host + " (" + notes.join(" | ").take(300) + ")")
        }
    }

    // Gateway catalogues
    if (payload == null && (!forcedMode || forcedMode == "gateway")) {
        def rows = []
        int v2code = 0, v4code = 0
        def v2Paths = ["/sap/opu/odata/IWFND/CATALOGSERVICE;v=2/ServiceCollection?%24format=json",
                       "/sap/opu/odata/IWFND/CATALOGSERVICE/ServiceCollection?%24format=json"]
        for (p in v2Paths) {
            def gw = retryingCall(host + p + qsExtra, auth, "GET", 5000, timeoutMs, airHeaders,
                                  retryAttempts, retryDelayMs, retryMultiplier, notes)
            v2code = (int) gw.code
            if (gw.code == 200) {
                def v2rows = gatewayRowsOf((String) gw.body)
                if (v2rows != null) {
                    rows.addAll(v2rows)
                    notes << ("gateway v2: " + v2rows.size() + " catalogue rows from " + p.replaceAll(/\?.*$/, ""))
                } else {
                    notes << ("gateway v2: unparseable response from " + p.replaceAll(/\?.*$/, "") + ", ignored")
                }
                break
            }
            notes << ("gateway v2: " + gw.code + " from " + p.replaceAll(/\?.*$/, "") +
                      (gw.code == 403 ? " (expected on S/4HANA Cloud)" : ""))
            if (gw.code != 404) { break }
        }
        def v4 = retryingCall(host + "/sap/opu/odata4/iwfnd/config/default/iwfnd/catalog/0002/ServiceGroups" +
                              "?%24expand=DefaultSystem(%24expand%3DServices)" + qsExtra,
                              auth, "GET", 5000, timeoutMs, airHeaders,
                              retryAttempts, retryDelayMs, retryMultiplier, notes)
        v4code = (int) v4.code
        if (v4.code == 200) {
            def entries = v4CatalogEntries((String) v4.body, v4bySquash, v4byGroup, notes)
            def toVerify = entries.findAll { it.needsVerify }
            if (toVerify) {
                def verified = runParallel(toVerify, 16, 45) { e ->
                    def hit = null
                    for (p in e.candidates) {
                        def r = probeOne(host, auth, [path: p], false, 3000, 8000, airHeaders, qsExtra)
                        if (r.ok) { hit = p; break }
                    }
                    return hit
                }
                verified.eachWithIndex { hit, i ->
                    if (hit) { toVerify[i].path = hit; toVerify[i].needsVerify = false }
                    else { notes << ("gateway v4: dropped " + toVerify[i].name + " (no candidate address answered)") }
                }
                entries = entries.findAll { !it.needsVerify }
            }
            entries.each { e ->
                rows << [ID: e.name, Title: e.name, Description: e.title,
                         ServiceUrl: host + e.path, MetadataUrl: host + e.path + "/\$metadata"]
            }
            notes << ("gateway v4: " + entries.size() + " services from the V4 catalogue")
        } else {
            notes << ("gateway v4: " + v4.code + (v4.code == 403 ? " (expected on S/4HANA Cloud)" : ""))
        }
        if (!rows && !forcedMode) {
            boolean refused = v2code == 401 || v4code == 401
            boolean unreachable = v2code <= 0 && v4code <= 0
            boolean failing = (v2code <= 0 || v2code >= 500) && (v4code <= 0 || v4code >= 500)
            if (refused || unreachable || failing) {
                String sentence = "SAP answered the catalogue requests with a server error."
                if (refused) {
                    sentence = "SAP refused the sign-in (HTTP 401). Check the user and password of the " +
                               "security material named in S4_CREDENTIAL_ALIAS, and that the user is " +
                               "not locked in SAP."
                } else if (unreachable) {
                    sentence = "SAP could not be reached. Check S4_HOSTNAME, the network route and the " +
                               "certificate of the system."
                }
                throw new IllegalStateException("UPSTREAM: " + sentence + " Host " + host +
                                                ", V2 catalogue HTTP " + v2code +
                                                ", V4 catalogue HTTP " + v4code + ".")
            }
        }
        if (rows) {
            if (checkAuth) {
                authorizeRows(host, auth, rows, view, idx, airHeaders, qsExtra, userName, notes)
            } else {
                notes << "authorization check skipped (CATALOG_CHECK_AUTHORIZATION=false or ?filter=none)"
            }
            payload = JsonOutput.toJson([d: [results: rows]])
            source = "gateway"
        } else if (forcedMode == "gateway") {
            throw new IllegalStateException("UPSTREAM: Gateway catalogue is not readable on " + host +
                                            " (V2 HTTP " + v2code + ", V4 HTTP " + v4code + ")")
        }
    }

    // Service probe
    if (payload == null) {
        long budgetStart = java.time.Instant.now().toEpochMilli()
        def results = runParallel(candidates, 24, 45) { cand ->
            probeOne(host, auth, cand, filterByUser, 3000, 6000, airHeaders, qsExtra)
        }
        def entries = []
        int okCount = 0, closedCount = 0, errCount = 0
        results.eachWithIndex { r, i ->
            if (r == null) { errCount++; return }
            if (r.ok) { okCount++; entries << candidates[i] }
            else if (r.code == 401 || r.code == 403) { closedCount++ }
            else { errCount++ }
        }
        long probeMs = java.time.Instant.now().toEpochMilli() - budgetStart
        notes << ("probe: " + candidates.size() + " candidates in " + probeMs + " ms -> " +
                  okCount + " usable, " + closedCount + " absent-or-denied, " + errCount + " errors" +
                  (filterByUser ? "" : " (existence only, no authorization knock)"))
        payload = buildPayload(host, entries)
        source = "probe"
    }

    // Cache write
    if (dataStore != null && !forcedMode && payload != null && countResults(payload) > 0 &&
            filterRows(gatewayRowsOf(payload) ?: [], view, "both").rows.size() > 0) {
        try {
            def env = JsonOutput.toJson([cachedAt: java.time.Instant.now().toEpochMilli(), source: source, payload: payload])
            def bean = new DataBean()
            bean.setDataAsArray(env.getBytes("UTF-8"))
            def cfg = new DataConfig()
            cfg.setStoreName(STORE)
            cfg.setId(cacheId)
            cfg.setOverwrite(true)
            dataStore.put(bean, cfg)
        } catch (Exception e) {
            notes << ("cache write skipped: " + e.getMessage())
        }
    }

    respond(message, messageLog, messageId, payload, source, notes, t0, view, odata)
    return message
}

static List resolveArrangements(List arrs, List inbound, List users, String userName, boolean filterByUser,
                                Map v2byName, Map v2bySquash, Map v4byGroup, List notes) {
    def allowed = null
    if (filterByUser) {
        allowed = users.findAll { (it["UserName"] ?: "").toString().equalsIgnoreCase(userName) }
                       .collect { it["CommunicationArrangementUUID"] } as Set
        if (allowed.isEmpty()) {
            notes << ("no arrangement is assigned to user " + userName + " - result will be empty; " +
                      "assign the communication user to an arrangement or call with filter=none")
        }
    }
    def scenarioOf = [:]
    arrs.each { a ->
        scenarioOf[a["CommunicationArrangementUUID"]] =
            (a["CommunicationScenarioName"] ?: a["CommunicationScenarioID"] ?: "").toString()
    }
    def byPath = [:]
    inbound.each { r ->
        if ("true".equalsIgnoreCase((r["IsHidden"] ?: "").toString())) { return }
        if (allowed != null && !allowed.contains(r["CommunicationArrangementUUID"])) { return }
        def st = r["ServiceType"]
        def id = (r["ServiceID"] ?: "").toString()
        if (!id) { return }
        def scenario = scenarioOf[r["CommunicationArrangementUUID"]] ?: ""
        if (st == "IWSG") {
            def name = stripServiceSuffix(id, "IWSG")
            def hit = v2byName[name.toUpperCase()] ?: v2bySquash[squash(name)]
            if (hit == null && id.length() >= 30) {
                def pref = v2bySquash.keySet().findAll { it.startsWith(squash(name)) }
                if (pref.size() == 1) { hit = v2bySquash[pref[0]] }
            }
            if (hit != null) {
                byPath[hit.path] = [name: hit.name, title: hit.title, path: hit.path,
                                    deprecated: hit.deprecated, needsVerify: false]
            } else {
                def path = "/sap/opu/odata/sap/" + name
                byPath[path] = [name: name, title: scenario ? "via " + scenario : name,
                                path: path, deprecated: false, needsVerify: true]
            }
        } else if (st == "G4BA") {
            def group = stripServiceSuffix(id, "G4BA")
            def hits = v4byGroup[squash(group)]
            if (hits == null && id.length() >= 30) {
                def pref = v4byGroup.keySet().findAll { it.startsWith(squash(group)) }
                if (pref.size() == 1) { hits = v4byGroup[pref[0]] }
            }
            if (hits == null) {
                notes << ("unresolved V4 service " + id + " (not in the shipped release list; " +
                          "enter its Service Path manually in Keboola if needed)")
                return
            }
            boolean multi = hits.size() > 1
            hits.each { hit ->
                byPath[hit.path] = [name: hit.name, title: hit.title, path: hit.path,
                                    deprecated: hit.deprecated, needsVerify: multi]
            }
        }
    }
    return byPath.values().toList().sort { it.name }
}

static String buildPayload(String host, List entries) {
    def seen = [] as Set
    def results = entries.findAll { e -> seen.add(e.path) }.collect { e ->
        [ID         : e.name,
         Title      : e.name,
         Description: e.deprecated ? (e.title + " (deprecated)") : e.title,
         ServiceUrl : host + e.path,
         MetadataUrl: host + e.path + "/\$metadata"]
    }
    return JsonOutput.toJson([d: [results: results]])
}

static List gatewayRowsOf(String body) {
    try {
        def j = parseJson(body)
        if (j instanceof Map && j["d"] instanceof Map && j["d"]["results"] instanceof List) {
            return j["d"]["results"].findAll { it instanceof Map }
        }
    } catch (Exception ignored) { }
    return null
}

static List v4CatalogEntries(Object body, Map v4bySquash, Map v4byGroup, List notes) {
    def out = []
    def seen = [] as Set
    def j
    try { j = parseJson(body) } catch (Exception e) {
        notes << ("gateway v4: unparseable response, ignored (" + (e.getMessage() ?: "") + ")")
        return out
    }
    def groups = (j instanceof Map && j["value"] instanceof List) ? j["value"] : []
    groups.each { g ->
        if (!(g instanceof Map)) { return }
        def groupId = (g["GroupId"] ?: g["GroupID"] ?: g["ID"] ?: g["Id"] ?: "").toString()
        def services = []
        if (g["DefaultSystem"] instanceof Map && g["DefaultSystem"]["Services"] instanceof List) {
            services = g["DefaultSystem"]["Services"]
        } else if (g["Services"] instanceof List) {
            services = g["Services"]
        }
        services.each { s ->
            if (!(s instanceof Map)) { return }
            def id = (s["ServiceId"] ?: s["ServiceID"] ?: s["ID"] ?: s["Id"] ?: "").toString()
            if (!id) { return }
            def version = (s["ServiceVersion"] ?: "0001").toString().padLeft(4, "0")
            def title = (s["Description"] ?: s["ServiceAlias"] ?: id).toString()
            def key = (id + "|" + version).toUpperCase()
            if (seen.contains(key)) { return }
            seen << key
            def url = (s["ServiceUrl"] ?: s["ServiceURL"] ?: "").toString().trim()
            if (url) {
                def path = url.replaceAll(/^https?:\/\/[^\/]+/, "").replaceAll(/\?.*$/, "").replaceAll(/\/+$/, "")
                if (!path.startsWith("/")) { path = "/" + path }
                out << [name: id, title: title, path: path, deprecated: false, needsVerify: false]
                return
            }
            def hit = v4bySquash[squash(id)]
            if (hit == null && groupId) {
                def hits = v4byGroup[squash(groupId)]
                if (hits != null && hits.size() == 1) { hit = hits[0] }
            }
            if (hit != null) {
                out << [name: id, title: title, path: hit.path, deprecated: hit.deprecated, needsVerify: false]
                return
            }
            def grp = (groupId ?: id).toLowerCase()
            def svc = id.toLowerCase()
            def candidates = ["/sap/opu/odata4/sap/" + grp + "/srvd_a2x/sap/" + svc + "/" + version,
                              "/sap/opu/odata4/sap/" + grp + "/srvd/sap/" + svc + "/" + version]
            out << [name: id, title: title, path: candidates[0], deprecated: false,
                    needsVerify: true, candidates: candidates]
        }
    }
    return out
}

static String technicalName(String s) {
    if (s == null) { return "" }
    return s.toUpperCase()
            .replaceFirst(/^\/[^\/]+\//, "")
            .replaceFirst(/^[ZY](?=(API|UI|C)_)/, "")
            .replaceFirst(/_\d{4}$/, "")
}

static boolean isUiRow(Map r) {
    for (k in ["TechnicalServiceName", "Title", "ID"]) {
        def v = r[k]?.toString()
        if (v && technicalName(v).startsWith("UI_")) { return true }
    }
    return false
}

static String viewOf(String raw, String configured) {
    def v = (raw ?: "").trim().toLowerCase()
    return (v in ["interfaces", "extended", "all"]) ? v : configured
}

static boolean customerNamespace(String id) {
    def s = (id ?: "").toUpperCase()
    return s.startsWith("Z") || s.startsWith("Y")
}

static boolean inView(Map r, String view) {
    if (view == "all") { return true }
    if (isUiRow(r)) { return false }
    def name = technicalName((r["Title"] ?: r["ID"] ?: "").toString())
    def url = (r["ServiceUrl"] ?: "").toString()
    boolean v4 = url.contains("/sap/opu/odata4/")
    if (r.containsKey("ServiceType")) {
        def type = (r["ServiceType"] ?: "").toString().toUpperCase()
        if (type == "UI") { return false }
        if (view == "extended") { return true }
        boolean customerBuilt = (r["IsSapService"]?.toString() ?: "").equalsIgnoreCase("false")
        return type == "WEB_API" || customerBuilt || name.startsWith("API_")
    }
    if (v4 && !url.contains("/srvd_a2x/")) {
        return customerNamespace((r["ID"] ?: "").toString()) && !name.startsWith("I_")
    }
    return true
}

static void authorizeRows(String host, String auth, List rows, String view, Map idx,
                          Map airHeaders, String qsExtra, String userName, List notes) {
    def knockView = (view == "all") ? "interfaces" : view
    def targets = rows.findAll { inView(it, knockView) }
    if (!targets) { return }
    long t0 = java.time.Instant.now().toEpochMilli()
    def cands = targets.collect { r ->
        def path = (r["ServiceUrl"] ?: "").toString()
                       .replaceAll(/^https?:\/\/[^\/]+/, "").replaceAll(/\?.*$/, "").replaceAll(/\/+$/, "")
        def name = technicalName((r["Title"] ?: r["ID"] ?: "").toString())
        def hit = idx.v2byName[name] ?: idx.v2bySquash[squash(name)] ?: idx.v4bySquash[squash(name)]
        [path: path, set: hit?.set, set2: hit?.set2]
    }
    def results = runParallel(cands, 40, 40) { cand ->
        cand.path ? probeOne(host, auth, cand, true, 3000, 6000, airHeaders, qsExtra) : null
    }
    int yes = 0, no = 0, unknown = 0
    def denied = []
    def why = [:]
    int broken = 0
    results.eachWithIndex { r, i ->
        String verdict
        if (r == null) { verdict = "unknown"; why["budget"] = (why["budget"] ?: 0) + 1 }
        else if (r.ok) { verdict = "yes" }
        else if (r.code == 401 || r.code == 403) { verdict = "no" }
        else if (r.code >= 500 && r.code != 501) {
            verdict = "error"; broken++
            targets[i]["ServiceError"] = r.code.toString()
            def k = r.stage + "@" + r.code; why[k] = (why[k] ?: 0) + 1
        }
        else { verdict = "unknown"; def k = r.stage + "@" + r.code; why[k] = (why[k] ?: 0) + 1 }
        targets[i]["Authorized"] = verdict
        if (verdict == "yes") { yes++ } else if (verdict == "no") { no++; denied << technicalName((targets[i]["Title"] ?: targets[i]["ID"] ?: "").toString()) } else if (verdict == "unknown") { unknown++ }
    }
    notes << ("authorization check (" + knockView + " view, user " + userName + "): " + targets.size() +
              " services exist, " + yes + " readable, " + no + " not authorized, " + broken +
              " answer a server error, " + unknown + " not verified, in " +
              (java.time.Instant.now().toEpochMilli() - t0) + " ms" +
              (why ? " (detail: " + why.collect { k, v -> k + "=" + v }.join(", ") + ")" : ""))
    if (yes == 0 && no > 0) {
        notes << ("user " + userName + " is not authorized for any of the " + targets.size() +
                  " listed services - the list is empty until an administrator grants the " +
                  "service authorizations (S_SERVICE) or the catalogue view is widened")
    }
    if (denied) { notes << ("not authorized: " + denied.sort().take(200).join(", ") + (denied.size() > 200 ? ", ..." : "")) }
}

static Map filterRows(List rows, String view, String odata) {
    int hidden = 0
    int hiddenVersion = 0
    def kept = rows.findAll { r ->
        boolean keep = inView(r, view)
        if (keep && view != "all" && (r["Authorized"]?.toString() in ["no", "error"])) { keep = false }
        if (keep && odata != "both" && odataVersionOf(r) != odata) { keep = false; hiddenVersion++ }
        if (!keep) { hidden++ }
        return keep
    }
    kept.each { r ->
        def d = (r["Description"] ?: "").toString()
        def ver = odataVersionOf(r)
        if (ver && !d.contains("(OData V")) {
            d = (d ? d + " " : "") + "(OData " + ver.toUpperCase() + ")"
        }
        if ("DEPRECATED".equalsIgnoreCase((r["ReleaseStatus"] ?: "").toString()) && !d.contains("(deprecated)")) {
            d = (d ? d + " " : "") + "(deprecated)"
        }
        def verdict = r["Authorized"]?.toString()
        if (verdict == "no" && !d.contains("(user not authorized)")) {
            d = (d ? d + " " : "") + "(user not authorized)"
        } else if (verdict == "error" && !d.contains("(service error")) {
            d = (d ? d + " " : "") + "(service error HTTP " + (r["ServiceError"] ?: "5xx") + ")"
        } else if (verdict == "unknown" && !d.contains("(not verified)")) {
            d = (d ? d + " " : "") + "(not verified)"
        }
        r["Description"] = d
    }
    kept = kept.sort { a, b ->
        def ia = technicalName((a["Title"] ?: a["ID"] ?: "").toString())
        def ib = technicalName((b["Title"] ?: b["ID"] ?: "").toString())
        int ra = ia.startsWith("API_") ? 0 : 1
        int rb = ib.startsWith("API_") ? 0 : 1
        return (ra != rb) ? (ra <=> rb) : (ia <=> ib)
    }
    return [rows: kept, hidden: hidden, hiddenVersion: hiddenVersion]
}

static String odataVersionOf(Map r) {
    def u = (r["ServiceUrl"] ?: r["MetadataUrl"] ?: "").toString().toLowerCase()
    if (u.contains("/sap/opu/odata4/")) { return "v4" }
    if (u.contains("/sap/opu/odata/")) { return "v2" }
    return ""
}

static String odataOf(String raw) {
    def v = (raw ?: "").trim().toLowerCase()
    if (v in ["v2", "2"]) { return "v2" }
    if (v in ["v4", "4"]) { return "v4" }
    return "both"
}

static List runParallel(List items, int threads, int budgetSeconds, Closure work) {
    ExecutorService pool = Executors.newFixedThreadPool(threads)
    try {
        def futures = items.collect { item -> pool.submit({ work(item) } as Callable) }
        long deadline = java.time.Instant.now().toEpochMilli() + budgetSeconds * 1000L
        return futures.collect { f ->
            long left = deadline - java.time.Instant.now().toEpochMilli()
            try {
                return f.get(Math.max(left, 0L), TimeUnit.MILLISECONDS)
            } catch (Exception ignored) {
                f.cancel(true)
                return null
            }
        }
    } finally {
        pool.shutdownNow()
    }
}

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

static int toInt(String v, int dflt) {
    try { return (v != null && v.isInteger()) ? v.toInteger() : dflt } catch (Exception ignored) { return dflt }
}

static long toLong(String v, long dflt) {
    try { return (v != null && v.isLong()) ? v.toLong() : dflt } catch (Exception ignored) { return dflt }
}

static Map indexCandidates(List candidates) {
    def v2byName = [:]
    def v2bySquash = [:]
    def v4byGroup = [:]
    def v4bySquash = [:]
    candidates.each { c ->
        if (c.odata == "v2") {
            v2byName[c.name.toUpperCase()] = c
            v2bySquash[squash(c.name)] = c
        } else {
            v4bySquash[squash(c.name)] = c
            def seg = c.path.split("/")
            if (seg.length > 5) {
                def g = squash(seg[5])
                if (!v4byGroup.containsKey(g)) { v4byGroup[g] = [] }
                v4byGroup[g] << c
            }
        }
    }
    return [v2byName: v2byName, v2bySquash: v2bySquash, v4byGroup: v4byGroup, v4bySquash: v4bySquash]
}

static Map parseQuery(String q) {
    def out = [:]
    if (!q) { return out }
    q.split("&").each { pair ->
        def i = pair.indexOf("=")
        if (i > 0) { out[java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                         java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8") }
    }
    return out
}

static int countResults(String payload) {
    try {
        def j = parseJson(payload)
        return j["d"]["results"].size()
    } catch (Exception ignored) { return -1 }
}

static void respond(Message message, def messageLog, String messageId, String payload,
                    String source, List notes, long t0, String view, String odata) {
    def rows = gatewayRowsOf(payload) ?: []
    def filtered = filterRows(rows, view, odata)
    int count = filtered.rows.size()
    int denied = rows.count { "no".equals(it["Authorized"]?.toString()) }
    int broken = rows.count { "error".equals(it["Authorized"]?.toString()) }
    if (filtered.hidden > 0) {
        notes << ("view " + view + ": hid " + filtered.hidden + " of " + rows.size() +
                  " service(s) (UI apps, raw CDS views, technical services" +
                  (denied > 0 ? ", " + denied + " not authorized for this user" : "") +
                  (broken > 0 ? ", " + broken + " answering a server error" : "") +
                  ") - ?include=extended or ?include=all widens the list")
    }
    if (filtered.hiddenVersion > 0) {
        notes << ("odata " + odata + ": hid " + filtered.hiddenVersion + " service(s) speaking the other OData version")
    }
    def answer = [results: filtered.rows]
    if ("true".equals(message.getProperty("CATALOG_DEBUG")?.toString())) {
        answer["diagnostics"] = [messageId: messageId, source: source, view: view, notes: notes]
    }
    def airHeaderName = readCfg(message, "CFG_AIR_HEADER_NAME")
    if (airHeaderName) { message.setHeader(airHeaderName, null) }
    ["Content-Language", "ETag", "Last-Modified", "Cache-Control", "DataServiceVersion", "OData-Version",
     "sap-message", "sap-messagescount", "Location", "Retry-After"].each { message.setHeader(it, null) }
    message.setBody(JsonOutput.toJson([d: answer]))
    message.setHeader("CamelHttpResponseCode", 200)
    message.setHeader("Content-Type", "application/json")
    message.setHeader("X-Keboola-Catalog-Source", source ?: "unknown")
    message.setHeader("X-Keboola-Catalog-View", view + ";shown=" + count + ";hidden=" + filtered.hidden +
                                                ";denied=" + denied + ";broken=" + broken +
                                                (odata != "both" ? ";odata=" + odata : ""))
    String status
    def src = (source ?: "unknown")
    String base = src.replaceFirst(/^cache\(([^,)]+).*$/, '$1')
    if (count == 0) {
        status = "CATALOG_EMPTY"
    } else if (base.startsWith("arrangements") || base.startsWith("gateway")) {
        status = "OK"
    } else {
        status = "DEGRADED_" + base.toUpperCase().replaceAll(/[^A-Z0-9_]/, "_")
    }
    message.setProperty("SAP_MessageProcessingLogCustomStatus", status)
    if (messageLog != null) {
        messageLog.addCustomHeaderProperty("CatalogSource", source)
        messageLog.addCustomHeaderProperty("CatalogCount", count.toString())
        messageLog.addCustomHeaderProperty("CatalogHidden", filtered.hidden.toString())
        messageLog.addCustomHeaderProperty("CatalogView", view)
        messageLog.addCustomHeaderProperty("CatalogDenied", denied.toString())
        messageLog.addCustomHeaderProperty("UpstreamOutcome", "OK")
        messageLog.setStringProperty("CatalogSource", source)
        messageLog.setStringProperty("DurationMs", (java.time.Instant.now().toEpochMilli() - t0).toString())
        if (notes) {
            String joined = notes.join(" | ")
            messageLog.setStringProperty("CatalogDiagnostics",
                joined.length() > 2000 ? joined.substring(0, 2000) + " ..." : joined)
        }
    }
}

static List loadCandidates() {
    def out = []
    serviceCatalogRaw().split("\n").each { line ->
        if (!line) { return }
        def p = line.split("\\|", 7)
        if (p.length < 7) { return }
        out << [name: p[0], odata: p[1], path: p[2], deprecated: p[3] == "1",
                set: p[4], set2: p[5], title: p[6]]
    }
    return out
}

static String serviceCatalogRaw() {
    return svcChunk0() + svcChunk1() + svcChunk2()
}
private static String svcChunk0() {
    return 'APICATALOGPROFILE_0001|v4|/sap/opu/odata4/sap/api_catalogprofile/srvd_a2x/sap/apicatalogprofile/0001|0|MaintNotifCatalogAssignment|MaintNotifCatalogProfile|Catalog Profile - Read\nAPICAUSE_0001|v4|/sap/opu/odata4/sap/api_cause/srvd_a2x/sap/apicause/0001|0|MaintNotifCauseCode||Catalog Type Cause - Read\nAPI_APAR_SEPA_MANDATE_SRV|v2|/sap/opu/odata/sap/API_APAR_SEPA_MANDATE_SRV|0|SEPAMandateSet|SEPAMandateUsageSet|SEPA Mandate - Manage in Accounts Receivable\nAPI_BANKDETAIL_SRV|v2|/sap/opu/odata/sap/API_BANKDETAIL_SRV|0|A_BankDetail||Bank - Read\nAPI_BATCH_SRV|v2|/sap/opu/odata/sap/API_BATCH_SRV|0|Batch|BatchCharc|Batch Master Record\nAPI_BILLING_DOCUMENT_REQUEST_SRV|v2|/sap/opu/odata/sap/API_BILLING_DOCUMENT_REQUEST_SRV|0|A_BillingDocReqItemPartner|A_BillingDocReqItemPrcgElmnt|Billing Document Request - Read, Reject, Delete\nAPI_BILLING_DOCUMENT_SRV|v2|/sap/opu/odata/sap/API_BILLING_DOCUMENT_SRV|0|A_BillingDocument|A_BillingDocumentItem|Billing Document - Read, Cancel, GetPDF\nAPI_BILLOFMATERIAL_COMPARISON_SRV|v2|/sap/opu/odata/sap/API_BILLOFMATERIAL_COMPARISON_SRV|0|ComparisonResultSet||Bills of Material Comparison\nAPI_BILL_OF_MATERIAL_SRV_0002|v2|/sap/opu/odata/sap/API_BILL_OF_MATERIAL_SRV|0|A_BillOfMaterial|A_BillOfMaterialItem|Bills of Material\nAPI_BOM_WHERE_USED_SRV|v2|/sap/opu/odata/sap/API_BOM_WHERE_USED_SRV|0|A_BOMWhereUsed||Bills of Material Where-Used List\nAPI_BUFFERSIZING_SRV|v2|/sap/opu/odata/sap/API_BUFFERSIZING_SRV|0|A_DemandAdjustments|A_DemandAdjustmentFactors|Buffer Sizing\nAPI_BUFFER_PROFILE_SRV|v2|/sap/opu/odata/sap/API_BUFFER_PROFILE_SRV|0|A_ProfileDetails|A_ProfileAssignedToPlant|Buffer Profile - Read\nAPI_BUSINESSAREA_SRV|v2|/sap/opu/odata/sap/API_BUSINESSAREA_SRV|0|A_BusinessArea|A_BusinessAreaText|Business Area - Read\nAPI_BUSINESS_PARTNER|v2|/sap/opu/odata/sap/API_BUSINESS_PARTNER|0|A_AddressEmailAddress|A_AddressFaxNumber|Business Partner (A2X)\nAPI_BUSINESS_SITUATION_SRV|v2|/sap/opu/odata/sap/API_BUSINESS_SITUATION_SRV|0|A_SitnDataContext|A_SitnInstance|Business Situation - Read\nAPI_BUS_SITN_MSTRDATA_SRV|v2|/sap/opu/odata/sap/API_BUS_SITN_MSTRDATA_SRV|0|SituationAction|SituationActionText|Business Situation Type - Read\nAPI_BUS_SOLUTION_ORDER_SRV|v2|/sap/opu/odata/sap/API_BUS_SOLUTION_ORDER_SRV|0|A_BSOrdSrvcContrItmBillgReqItm|A_BusinessSolutionOrder|Business Solution Order (A2X)\nAPI_CABUSPARTINVOICE_0002|v4|/sap/opu/odata4/sap/api_cabuspartinvoice/srvd_a2x/sap/api_cabuspartinvoice/0002|0|CABPInvcCorrespncEnhcd|CABPInvcEnhcdForDspCrcy|Contract Accounting Business Partner Invoice – Read\nAPI_CABUSPARTPAYMENT_0002|v4|/sap/opu/odata4/sap/api_cabuspartpayment/srvd_a2x/sap/api_cabuspartpayment/0002|0|CABPPaytEnhcdForDspCrcy|CABPPaytItemEnhcdForDspCrcy|Contract Accounting Business Partner Payment – Manage\nAPI_CDR_FILE_DOWNLOAD_SRV|v2|/sap/opu/odata/sap/API_CDR_FILE_DOWNLOAD_SRV|0|ListFiles|DownloadFile|Customer Data Return - File Download\nAPI_CENTRAL_PURCHASECONTRACT_SRV|v2|/sap/opu/odata/sap/API_CENTRAL_PURCHASECONTRACT_SRV|0|A_CentralPurchaseContract|A_CePuCoDistrAcctAssgmt|Central Purchase Contract\nAPI_CHANGEMASTER_0002|v2|/sap/opu/odata/sap/API_CHANGEMASTER|0|A_ChangeMaster|A_ChangeMasterAltDate|Change Master\nAPI_CHANGE_RECORD|v2|/sap/opu/odata/sap/API_CHANGE_RECORD|1|A_ChangeRecord|A_ChangeRecordLifeCycleStatus|Change Record\nAPI_CHARCATTRIBUTECATALOG_SRV|v2|/sap/opu/odata/sap/API_CHARCATTRIBUTECATALOG_SRV|0|A_CharcAttribSeldCodeSet|A_CharcAttribSeldSetCode|Characteristic Attribute Catalog - Read\nAPI_CHARTOFACCOUNTS_SRV|v2|/sap/opu/odata/sap/API_CHARTOFACCOUNTS_SRV|0|A_ChartOfAccounts|A_ChartOfAccountsText|Chart of Accounts - Read\nAPI_CLFN_CHARACTERISTIC_SRV|v2|/sap/opu/odata/sap/API_CLFN_CHARACTERISTIC_SRV|0|A_ClfnCharacteristicForKeyDate|A_ClfnCharcDescForKeyDate|Characteristic Data for Classification\nAPI_CLFN_CLASS_SRV|v2|/sap/opu/odata/sap/API_CLFN_CLASS_SRV|0|A_ClfnClassCharcForKeyDate|A_ClfnClassDescForKeyDate|Class Data for Classification\nAPI_CLFN_PRODUCT_SRV|v2|/sap/opu/odata/sap/API_CLFN_PRODUCT_SRV|0|A_ProductPlantProcurement|A_ProductStorageLocation|Product Master Data Including Classification – Read\nAPI_CN_BANK_RECONCILIAITON_SRV|v2|/sap/opu/odata/sap/API_CN_BANK_RECONCILIAITON_SRV|0|BankStatementItemSet|BankReconciliationItemSet|Bank Reconciliation Statement - Read, Reconcile (Synchronous)\nAPI_CN_PAYMENTRELEASELIST_APPR_SRV|v2|/sap/opu/odata/sap/API_CN_PAYMENTRELEASELIST_APPR_SRV|0|PaymentReleaseListHeaderSet|PaymentReleaseListItemSet|Payment Item - Read, Update (Synchronous)\nAPI_CN_VAT_INVOICE_SRV|v2|/sap/opu/odata/sap/API_CN_VAT_INVOICE_SRV|0|DeclarationPeriodSet|A_CN_TaxInputInvoice|Incoming VAT Invoice\nAPI_COMPANYCODE_SRV|v2|/sap/opu/odata/sap/API_COMPANYCODE_SRV|0|A_CompanyCode||Company Code - Read\nAPI_CONDITION_CONTRACT_TYPE|v2|/sap/opu/odata/sap/API_CONDITION_CONTRACT_TYPE|0|A_BusVolFldCombnSetTypeAssgmt|A_BusVolFldCombnTypeFldAssgmt|Condition Contract Type - Read\nAPI_CONSOLIDATIONUNIT|v2|/sap/opu/odata/sap/API_CONSOLIDATIONUNIT|0|ConsolidationUnitByTime|ConsolidationUnitByTimeVersion|Consolidation Unit\nAPI_CONTROLLINGAREA_SRV|v2|/sap/opu/odata/sap/API_CONTROLLINGAREA_SRV|0|A_ControllingArea||Controlling Area - Read\nAPI_CONTROLLINGDEBITCREDITCODE_SRV|v2|/sap/opu/odata/sap/API_CONTROLLINGDEBITCREDITCODE_SRV|0|A_ControllingDebitCreditCode|A_ControllingDebitCreditCodeT|Debit/Credit Indicator CO - Read\nAPI_COSTCENTERACTIVITYTYPE_SRV|v2|/sap/opu/odata/sap/API_COSTCENTERACTIVITYTYPE_SRV|0|A_CostCenterActivityType|A_CostCenterActivityTypeText|Activity Type - Read (A2X)\nAPI_COSTCENTER_SRV|v2|/sap/opu/odata/sap/API_COSTCENTER_SRV|0|A_CostCenter|A_CostCenterText|Cost Center - Read (A2X)\nAPI_COSTCNTRACTIVITYTYPE_CRUD_SRV|v2|/sap/opu/odata/sap/API_COSTCNTRACTIVITYTYPE_CRUD_SRV|0|A_CostCenterActivityType|A_CostCenterActivityTypeText|Activity Type (A2X)\nAPI_COSTREVNREASSIGNMENT|v2|/sap/opu/odata/sap/API_COSTREVNREASSIGNMENT|0|A_CostRevenueReassignment|A_CostRevenueReassignmentItem|Journal Entry (Cost Controlling) - Read, Create\nAPI_COUNTRY_SRV|v2|/sap/opu/odata/sap/API_COUNTRY_SRV|0|A_Country|A_CountryText|Country/Region - Read\nAPI_CRDTMBUSINESSPARTNER|v2|/sap/opu/odata/sap/API_CRDTMBUSINESSPARTNER|0|CrdtMAcctCollateral|CrdtMAcctCrdtInsurance|Credit Management Master Data\nAPI_CREDIT_MEMO_REQUEST_SRV|v2|/sap/opu/odata/sap/API_CREDIT_MEMO_REQUEST_SRV|0|A_CrdMmReqItemSubsqntProcFlow|A_CreditMemoReqItemPartner|Credit Memo Request (A2X)\nAPI_CUSTOMERGROUP_SRV|v2|/sap/opu/odata/sap/API_CUSTOMERGROUP_SRV|0|A_CustomerGroup|A_CustomerGroupText|Customer Group - Read\nAPI_CUSTOMERSUPPLIERINDUSTRY_SRV|v2|/sap/opu/odata/sap/API_CUSTOMERSUPPLIERINDUSTRY_SRV|0|A_CustomerSupplierIndustry|A_CustomerSupplierIndustryText|Customer and Supplier Industry - Read\nAPI_CUSTOMER_MATERIAL_SRV|v2|/sap/opu/odata/sap/API_CUSTOMER_MATERIAL_SRV|0|A_CustomerMaterial||Customer Material (A2X)\nAPI_CUSTOMER_RETURNS_DELIVERY_SRV|v2|/sap/opu/odata/sap/API_CUSTOMER_RETURNS_DELIVERY_SRV|1|A_ReturnsDeliveryAddress|A_ReturnsDeliveryDocFlow|Customer Returns Delivery (A2X)\nAPI_CUSTOMER_RETURNS_DELIVERY_SRV_0002|v2|/sap/opu/odata/sap/API_CUSTOMER_RETURNS_DELIVERY_SRV|0|A_ReturnsDeliveryAddress|A_ReturnsDeliveryDocFlow|Customer Returns Delivery (A2X)\nAPI_CUSTOMER_RETURN_SRV|v2|/sap/opu/odata/sap/API_CUSTOMER_RETURN_SRV|0|A_CustomerReturn|A_CustomerReturnItem|Customer Return (A2X)\nAPI_CV_ATTACHMENT_SRV|v2|/sap/opu/odata/sap/API_CV_ATTACHMENT_SRV|0|AttachmentHarmonizedOperationSet|AttachmentForSAPObjectNodeTypeSet|Attachments\nAPI_DEBIT_MEMO_REQUEST_SRV|v2|/sap/opu/odata/sap/API_DEBIT_MEMO_REQUEST_SRV|0|A_DbtMemoReqItmSubsqntProcFlow|A_DebitMemoReqItemPartner|Debit Memo Request (A2X)\nAPI_DEFECTCATEGORY_SRV|v2|/sap/opu/odata/sap/API_DEFECTCATEGORY_SRV|0|A_DefectCategory|A_DefectCategoryText|Defect Category - Read\nAPI_DEFECTCLASS_SRV|v2|/sap/opu/odata/sap/API_DEFECTCLASS_SRV|0|A_DefectClass|A_DefectClassText|Defect Class - Read\nAPI_DEFECTCODE_SRV|v2|/sap/opu/odata/sap/API_DEFECTCODE_SRV|0|A_DefectCode|A_DefectCodeGroup|Defect Code and Code Group - Read\nAPI_DEFECT_SRV|v2|/sap/opu/odata/sap/API_DEFECT_SRV|1|A_Defect||Defect\nAPI_DEL_DOC_WITH_CREDIT_BLOCK|v2|/sap/opu/odata/sap/API_DEL_DOC_WITH_CREDIT_BLOCK|0|A_CreditBlockedDeliveryDoc|A_SalesDocumentRjcnReason|Delivery Document with Credit Block - Read, Check, Release, Reject (A2X)\nAPI_DISTRIBUTIONCHANNEL_SRV|v2|/sap/opu/odata/sap/API_DISTRIBUTIONCHANNEL_SRV|0|A_DistributionChannel|A_DistributionChannelText|Distribution Channel - Read\nAPI_DIVISION_SRV|v2|/sap/opu/odata/sap/API_DIVISION_SRV|0|A_Division|A_DivisionText|Division - Read\nAPI_DMS_PROCESS_SRV|v2|/sap/opu/odata/sap/API_DMS_PROCESS_SRV|0|A_DocInfoRecdObjLinkBOM|A_DocInfoRecdObjLinkChgRecd|Document Info Record\nAPI_EBRR_MANUAL_ACCRUALS_SRV|v2|/sap/opu/odata/sap/API_EBRR_MANUAL_ACCRUALS_SRV|0|ImportData|ReturnData|Manual Contract Accruals – Post (Synchronous)\nAPI_EHS_REPORT_INCIDENT_SRV|v2|/sap/opu/odata/sap/API_EHS_REPORT_INCIDENT_SRV|0|C_CurEHSLocationInclRootHier|C_EHSLocationValueHelp|Environment, Health and Safety Incident - Create, Read\nAPI_ENTERPRISE_PROJECT_SRV_0002|v2|/sap/opu/odata/sap/API_ENTERPRISE_PROJECT_SRV|0|A_EnterpriseProjBlkFunc|A_EnterpriseProject|Enterprise Project\nAPI_EVENT_BASED_REVREC_PROJECT_SRV|v2|/sap/opu/odata/sap/API_EVENT_BASED_REVREC_PROJECT_SRV|0|es_ActionResult||Event-Based Revenue Recognition Integration\nAPI_FCO_COST_RATE_SRV|v2|/sap/opu/odata/sap/API_FCO_COST_RATE_SRV|1|A_ActualCostRate|A_PlanCostRate|Cost Rate\nAPI_FCO_ICO_RESTRICTION|v2|/sap/opu/odata/sap/API_FCO_ICO_RESTRICTION|0|Allowlist||Allowlist Intercompany Postings – Read\nAPI_FINPLANNINGDATA_SRV|v2|/sap/opu/odata/sap/API_FINPLANNINGDATA_SRV|0|A_FinPlanningEntryItemTP|FinancialPlanData|Financial Planning Data - Write\nAPI_FINPLANNINGENTRYITEM_SRV|v2|/sap/opu/odata/sap/API_FINPLANNINGENTRYITEM_SRV|0|A_CompanyCode|A_CostCenter|Financial Planning Entry Item - Read\nAPI_FUNCTIONALAREA_SRV|v2|/sap/opu/odata/sap/API_FUNCTIONALAREA_SRV|0|A_FunctionalArea|A_FunctionalAreaText|Functional Area - Read\nAPI_GLACCOUNTINCHARTOFACCOUNTS_SRV|v2|/sap/opu/odata/sap/API_GLACCOUNTINCHARTOFACCOUNTS_SRV|0|A_GLAccountInChartOfAccounts|A_GLAccountText|G/L Account - Read\nAPI_GLACCOUNTLINEITEM|v2|/sap/opu/odata/sap/API_GLACCOUNTLINEITEM|0|GLAccountLineItem||G/L Account Line Items - Read (A2X)\nAPI_GLO_BUSINESSPLACE_SRV|v2|/sap/opu/odata/sap/API_GLO_BUSINESSPLACE_SRV|0|BusinessPlaceSet|BusinessPlaceCntryInfoSet|Business Place (Synchronous)\nAPI_GRMASTERDATA_SRV|v2|/sap/opu/odata/sap/API_GRMASTERDATA_SRV|0|ConsolidationAllMasterData|ConsolidationAllMDAttribText|Master Data for Group Reporting – Read\nAPI_GRTRANSACTIONDATA_SRV|v2|/sap/opu/odata/sap/API_GRTRANSACTIONDATA_SRV|0|GRTransactionDataResults|GRTransactionData|Transaction Data for Group Reporting - Read\nAPI_GTI_UPDATE_CUSTOMER_TYPE_SRV|v2|/sap/opu/odata/sap/API_GTI_UPDATE_CUSTOMER_TYPE_SRV|0|GTICustomerTypeSet||Golden Tax Interface Customer Type ‒ Read, Update\nAPI_INBOUND_DELIVERY_SRV|v2|/sap/opu/odata/sap/API_INBOUND_DELIVERY_SRV|1|A_MaintenanceItemObjList|A_InbDeliveryDocFlow|Inbound Delivery (A2X)\nAPI_INBOUND_DELIVERY_SRV_0002|v2|/sap/opu/odata/sap/API_INBOUND_DELIVERY_SRV|0|A_MaintenanceItemObjList|A_InbDeliveryDocFlow|Inbound Delivery (A2X)\nAPI_INFORECORD_PROCESS_SRV|v2|/sap/opu/odata/sap/API_INFORECORD_PROCESS_SRV|0|A_PurchasingInfoRecord|A_PurchasingInfoRecordNote|Purchasing Info Record\nAPI_INSPECTIONLOT_SRV|v2|/sap/opu/odata/sap/API_INSPECTIONLOT_SRV|0|A_InspectionCharacteristic|A_InspectionLot|Inspection Lot\nAPI_INSPECTIONMETHOD_SRV|v2|/sap/opu/odata/sap/API_INSPECTIONMETHOD_SRV|0|A_InspectionMethod|A_InspectionMethodText|Inspection Method - Read\nAPI_INSPECTIONPLAN_SRV|v2|/sap/opu/odata/sap/API_INSPECTIONPLAN_SRV|0|A_InspectionPlan|A_InspPlanDepdntCharc|Inspection Plan\nAPI_INTELLIGENTPRODUCTPROPOSAL_SRV|v2|/sap/opu/odata/sap/API_INTELLIGENTPRODUCTPROPOSAL_SRV|0|A_SalesDocumentProposalItem||Intelligent Product Proposal - Read\nAPI_JIT_CALL_PROCESS_SRV|v2|/sap/opu/odata/sap/API_JIT_CALL_PROCESS_SRV|1|A_JITCallCompGrp|A_JITCallCompMatl|Just-In-Time Calls\nAPI_JNTOPGAGRMT_0001|v4|/sap/opu/odata4/sap/api_jntopgagrmt/srvd_a2x/sap/api_jntopgagrmt/0001|0|A_JntOpgAgrmt|A_JntOpgAgrmtDrillingRatio|Joint Operating Agreement\nAPI_JOINTVENTURE_0001|v4|/sap/opu/odata4/sap/api_jointventure/srvd_a2x/sap/api_jointventure/0001|0|A_JntVntrFundCrcyByEquityGrp|A_JntVntrOvhdBurdenRatePct|Joint Venture\nAPI_JOURNALENTRYITEMBASIC_SRV|v2|/sap/opu/odata/sap/API_JOURNALENTRYITEMBASIC_SRV|0|A_CompanyCode|A_CostCenter|Journal Entry Item - Read\nAPI_JVA_BILLING_SRV|v2|/sap/opu/odata/sap/API_JVA_BILLING_SRV|0|AttachmentLinkSet||Joint Venture Billing - Read\nAPI_KANBAN_CONTROL_CYCLE_SRV_0002|v2|/sap/opu/odata/sap/API_KANBAN_CONTROL_CYCLE_SRV|0|||Kanban Control Cycle\nAPI_LEDGER_SRV|v2|/sap/opu/odata/sap/API_LEDGER_SRV|0|A_Ledger|A_LedgerText|Ledger - Read\nAPI_LEGALDOCUMENTSTATUS|v2|/sap/opu/odata/sap/API_LEGALDOCUMENTSTATUS|0|LegalDocumentStatus|LegalDocumentStatusText|Legal Document Status - Read\nAPI_LEGAL_CATEGORY_SRV|v2|/sap/opu/odata/sap/API_LEGAL_CATEGORY_SRV|0|A_LegalCategory|A_LegalCategoryText|Legal Categories - Read\nAPI_LEGAL_CONTENT_TYPE_SRV|v2|/sap/opu/odata/sap/API_LEGAL_CONTENT_TYPE_SRV|0|A_LglCntntMDocContentType|A_LglCntntMDocContentTypeText|Legal Document Content Types - Read\nAPI_LEGAL_DOCUMENT_SRV|v2|/sap/opu/odata/sap/API_LEGAL_DOCUMENT_SRV|1|A_LegalDocument|A_LglCntntMDocCategory|Legal Documents - Create, Read\nAPI_LEGAL_TRANSACTION_SRV|v2|/sap/opu/odata/sap/API_LEGAL_TRANSACTION_SRV|1|A_LegalTransaction|A_LglTransCategory|Legal Transactions\nAPI_LOCKBOXPOST_IN|v2|/sap/opu/odata/sap/API_LOCKBOXPOST_IN|0|LockboxBatch|LockboxBatchItem|Post Lockbox\nAPI_LOGBR_NOTAFISCAL_SRV|v2|/sap/opu/odata/sap/API_LOGBR_NOTAFISCAL_SRV|0|A_BR_NFAdditionalInformation|A_BR_NFAdditionImportDoc|Nota Fiscal – Create, Update\nAPI_MAINTENANCEBOM|v2|/sap/opu/odata/sap/API_MAINTENANCEBOM|0|BOMItem|BOMHeader|Maintenance Bill of Material\nAPI_MAINTENANCEITEM|v2|/sap/opu/odata/sap/API_MAINTENANCEITEM|0|MaintenanceItem|MaintenanceItemCause|Maintenance Item\nAPI_MAINTENANCEORDER|v2|/sap/opu/odata/sap/API_MAINTENANCEORDER|1|MaintenanceOrder|MaintenanceOrderLongText|Maintenance Order - Read\nAPI_MAINTENANCEPLAN|v2|/sap/opu/odata/sap/API_MAINTENANCEPLAN|0|MaintenanceItem|MaintenanceItemCause|Maintenance Plan\nAPI_MAINTNOTIFICATION|v2|/sap/opu/odata/sap/API_MAINTNOTIFICATION|0|MaintenanceNotification|MaintenanceNotificationItem|Maintenance Notification\nAPI_MAINTORDERCONFIRMATION|v2|/sap/opu/odata/sap/API_MAINTORDERCONFIRMATION|0|MaintOrderConfirmation|LongText|Maintenance Order Operation Confirmation\nAPI_MANAGE_SKILLTAGS_SRV|v2|/sap/opu/odata/SHCM/API_MANAGE_SKILLTAGS_SRV|0|SkillTagSet|SAP__FormatSet|Workforce Person SkillTag.\nAPI_MANAGE_WF_AVAILABILITY|v2|/sap/opu/odata/SHCM/API_MANAGE_WF_AVAILABILITY|0|TimeOverviewSet||Workforce Daily Availability\nAPI_MANAGE_WORKFORCE_TIMESHEET|v2|/sap/opu/odata/sap/API_MANAGE_WORKFORCE_TIMESHEET|0|TimeSheetEntryCollection||Workforce Timesheet\nAPI_MASTERINSPCHARACTERISTIC_SRV|v2|/sap/opu/odata/sap/API_MASTERINSPCHARACTERISTIC_SRV|0|A_InspectionSpecification|A_InspectionSpecificationText|Master Inspection Characteristic - Read\nAPI_MASTER_RECIPE|v2|/sap/opu/odata/sap/API_MASTER_RECIPE|0|ChangeRecordType|ChangeRecordTypeText|Master Recipe\nAPI_MATERIAL_DOCUMENT_SRV|v2|/sap/opu/odata/sap/API_MATERIAL_DOCUMENT_SRV|0|A_MaterialDocumentHeader|A_MaterialDocumentItem|Material Documents - Read, Create\nAPI_MATERIAL_STOCK_SRV|v2|/sap/opu/odata/sap/API_MATERIAL_STOCK_SRV|0|A_MaterialSerialNumber|A_MaterialStock|Material Stock - Read\nAPI_MATERIAL_VALUATION_SRV|v2|/sap/opu/odata/sap/API_MATERIAL_VALUATION_SRV|0|MaterialValuationSet||Material Price - Update\nAPI_MRP_MATERIALS_SRV_01|v2|/sap/opu/odata/sap/API_MRP_MATERIALS_SRV_01|0|MaterialCoverages|SupplyDemandItems|Material Planning Data - Read\nAPI_MSTRRCPPHSE_RELSHPTYPE|v2|/sap/opu/odata/sap/API_MSTRRCPPHSE_RELSHPTYPE|0|PhaseRelationshipType|PhaseRelationshipTypeText|Phase Relationship Type\nAPI_O2C_FICA_SEPA_MANDATE_SRV|v2|/sap/opu/odata/sap/API_O2C_FICA_SEPA_MANDATE_SRV|0|SEPAMandateSet|SEPAMandateUsageSet|SEPA Mandate – Manage in Contract Accounting\nAPI_OPLACCTGDOCITEMCUBE_SRV|v2|/sap/opu/odata/sap/API_OPLACCTGDOCITEMCUBE_SRV|0|A_OperationalAcctgDocItemCube||Accounting Document - Read\nAPI_ORDER_BILL_OF_MATERIAL_SRV|v2|/sap/opu/odata/sap/API_ORDER_BILL_OF_MATERIAL_SRV|0|A_BOMItemCategory|A_BOMItemCategoryText|Order Bills of Material\nAPI_OUTBOUND_DELIVERY_SRV|v2|/sap/opu/odata/sap/API_OUTBOUND_DELIVERY_SRV|1|A_OutbDeliveryItem|A_OutbDeliveryHeader|Outbound Delivery (A2X)\nAPI_OUTBOUND_DELIVERY_SRV_0002|v2|/sap/opu/odata/sap/API_OUTBOUND_DELIVERY_SRV|0|A_OutbDeliveryItem|A_OutbDeliveryHeader|Outbound Delivery (A2X)\nAPI_PACKINGINSTRUCTION|v2|/sap/opu/odata/sap/API_PACKINGINSTRUCTION|0|PackingInstructionComponent|PackingInstructionHeader|Packing Instruction – Read, Create (A2X)\nAPI_PARTNERCOMPANY_SRV|v2|/sap/opu/odata/sap/API_PARTNERCOMPANY_SRV|0|A_PartnerCompany||Trading Partner - Read\nAPI_PAYMENT_ADVICE_SRV|v2|/sap/opu/odata/sap/API_PAYMENT_ADVICE_SRV|0|A_PaymentAdvice|A_PaymentAdviceItem|Payment Advice (A2X)\nAPI_PAYMENT_METHOD_VALIDATION_SRV|v2|/sap/opu/odata/sap/API_PAYMENT_METHOD_VALIDATION_SRV|0|PaymentMethodValidationSet||Payment Method – Validate\nAPI_PERS_SETTLMT_DOC|v2|/sap/opu/odata/sap/API_PERS_SETTLMT_DOC|0|PersCompnElmntCostAssgmt|PersonnelCompensationElement|Personnel Settlement Document - Read\nAPI_PHYSICAL_INVENTORY_DOC_SRV|v2|/sap/opu/odata/sap/API_PHYSICAL_INVENTORY_DOC_SRV|0|A_PhysInventoryDocHeader|A_PhysInventoryDocItem|Physical Inventory Documents - Read, Create\nAPI_PLANNED_ORDERS|v2|/sap/opu/odata/sap/API_PLANNED_ORDERS|0|A_PlannedOrder|A_PlannedOrderCapacity|Planned Order\nAPI_PLANNINGCATEGORY_SRV|v2|/sap/opu/odata/sap/API_PLANNINGCATEGORY_SRV|0|A_PlanningCategory|A_PlanningCategoryText|Plan Category - Read\nAPI_PLND_INDEP_RQMT_SRV|v2|/sap/opu/odata/sap/API_PLND_INDEP_RQMT_SRV|0|PlannedIndepRqmt|PlannedIndepRqmtItem|Planned Independent Requirements\nAPI_PROCESS_ORDER_2_SRV|v2|/sap/opu/odata/sap/API_PROCESS_ORDER_2_SRV|0|A_ProcessOrderComponent_2|A_ProcessOrderItem_2|Process Order (Version 2)\nAPI_PROC_ORDER_CONFIRMATION_2_SRV|v2|/sap/opu/odata/sap/API_PROC_ORDER_CONFIRMATION_2_SRV|0|ProcOrdConf2|ProcOrdConfMatlDocItm|Process Order Confirmation\nAPI_PRODUCTGROUP_SRV|v2|/sap/opu/odata/sap/API_PRODUCTGROUP_SRV|0|A_ProductGroup|A_ProductGroupText|Product Group - Read\nAPI_PRODUCTIONSUPPLYAREA_SRV|v2|/sap/opu/odata/sap/API_PRODUCTIONSUPPLYAREA_SRV|0|A_ProductionSupplyArea|A_ProductionSupplyAreaAddress|Production Supply Area\nAPI_PRODUCTION_ORDER_2_SRV|v2|/sap/opu/odata/sap/API_PRODUCTION_ORDER_2_SRV|0|A_ProdnOrderItemSerialNumber|A_ProductionOrderComponent_2|Production Order (Version 2)\nAPI_PRODUCTION_ROUTING|v2|/sap/opu/odata/sap/API_PRODUCTION_ROUTING|1|OpInspCharcsAssgmt|ProductionRoutingOpCtrlPrflTxt|Production Routing\nAPI_PRODUCT_ALLOCATION_OBJECT_SRV|v2|/sap/opu/odata/sap/API_PRODUCT_ALLOCATION_OBJECT_SRV|0|A_ProdAllocationObject|A_ProdAllocationObjectT|Product Allocation Object\nAPI_PRODUCT_ALLOC_SEQUENCE_SRV|v2|/sap/opu/odata/sap/API_PRODUCT_ALLOC_SEQUENCE_SRV|0|A_ProdAllocationSequence|A_ProdAllocSequenceT|Product Allocation Sequence\nAPI_PRODUCT_AVAILY_INFO_BASIC|v2|/sap/opu/odata/sap/API_PRODUCT_AVAILY_INFO_BASIC|0|||Basic Product Availability Info\nAPI_PRODUCT_SRV|v2|/sap/opu/odata/sap/API_PRODUCT_SRV|0|A_Product|A_ProductBasicText|Product Master (A2X)\nAPI_PRODVOLCAPTURE|v2|/sap/opu/odata/sap/API_PRODVOLCAPTURE|0|ProductionVolume||Production Volume Capture - Read, Create\nAPI_PROD_ORDER_CONFIRMATION_2_SRV|v2|/sap/opu/odata/sap/API_PROD_ORDER_CONFIRMATION_2_SRV|0|ProdnOrdConfMatlDocItm|ProdnOrderConfBatchCharc|Production Order Confirmation\nAPI_PROFITCENTER_SRV|v2|/sap/opu/odata/sap/API_PROFITCENTER_SRV|0|A_PrftCtrCompanyCodeAssignment|A_ProfitCenter|Profit Center - Read (A2X)\nAPI_PROJECTDEMAND_0001|v2|/sap/opu/odata/sap/API_PROJECTDEMAND|0|A_ProjDmndExpenseDistr|A_ProjDmndResourceAssignment|Project Demand\nAPI_PURCHASECONTRACT_PROCESS_SRV_0002|v2|/sap/opu/odata/sap/API_PURCHASECONTRACT_PROCESS_SRV|0|A_PurchaseContract|A_PurchaseContractItem|Purchase Contracts\nAPI_PURCHASEORDER_PROCESS_SRV|v2|/sap/opu/odata/sap/API_PURCHASEORDER_PROCESS_SRV|1|A_POSubcontractingComponent|A_PurchaseOrder|Purchase Order\nAPI_PURCHASEREQ_PROCESS_SRV|v2|/sap/opu/odata/sap/API_PURCHASEREQ_PROCESS_SRV|1|A_PurchaseReqnItemText|A_PurchaseRequisitionHeader|Purchase Requisition\nAPI_PURCHASING_CATEGORY_SRV|v2|/sap/opu/odata/sap/API_PURCHASING_CATEGORY_SRV|0|A_PurgCat|A_PurgCatDescription|Purchasing Category\nAPI_PURCHASING_SOURCE_SRV|v2|/sap/opu/odata/sap/API_PURCHASING_SOURCE_SRV|0|A_PurchasingSource||Purchasing Source List\nAPI_PURGPRCGCONDITIONRECORD_SRV|v2|/sap/opu/odata/sap/API_PURGPRCGCONDITIONRECORD_SRV|0|A_PurgPrcgCndnRecdSuplmnt|A_PurgPrcgCndnRecdValidity|Condition Record for Pricing in Purchasing\nAPI_PURGPRICINGCONDITIONTYPE_SRV|v2|/sap/opu/odata/sap/API_PURGPRICINGCONDITIONTYPE_SRV|0|A_PurgPrcgCndnTypeText|A_PurgPricingConditionType|Condition Type for Pricing in Purchasing – Read\nAPI_PURGPRICINGPROCEDURE_SRV|v2|/sap/opu/odata/sap/API_PURGPRICINGPROCEDURE_SRV|0|A_PurgPrcgCndnTypeText|A_PurgPrcgProcedItemText|Pricing Procedure in Purchasing – Read\nAPI_PUR_QUOTA_ARRANGEMENT_SRV|v2|/sap/opu/odata/sap/API_PUR_QUOTA_ARRANGEMENT_SRV|0|A_PurchasingQuotaArrangement|A_PurgQuotaArrangementItem|Quota Arrangement\nAPI_QTN_PROCESS_SRV|v2|/sap/opu/odata/sap/API_QTN_PROCESS_SRV|0|A_SupplierQuotation|A_SupplierQuotationItem|Supplier Quotation - Create with Reference, Update, Delete\nAPI_QUALITYINFORECORD_SRV|v2|/sap/opu/odata/sap/API_QUALITYINFORECORD_SRV|0|QualityFirstArticleInspection|QualityInProcurement|Quality Info Record\nAPI_RAWSUBSTANCE|v2|/sap/opu/odata/sap/API_RAWSUBSTANCE|1|A_Product|A_ProductDescription|Raw Substance - Read\nAPI_REALSUBSTANCE|v2|/sap/opu/odata/sap/API_REALSUBSTANCE|1|A_Product|A_ProductDescription|Real Substance - Read\nAPI_RECIPE|v2|/sap/opu/odata/sap/API_RECIPE|1|A_Recipe|A_RecipeCharc|Recipe Header, Formula, and Process\nAPI_RECONTRACT_0001|v4|/sap/opu/odata4/sap/api_real_estate_contract/srvd_a2x/sap/api_recontract/0001|0|A_REContract|A_REContrAdjustmentTerm|Real Estate Contracts\nAPI_RESERVATION_DOCUMENT_SRV|v2|/sap/opu/odata/sap/API_RESERVATION_DOCUMENT_SRV|0|A_ReservationDocumentHeader|A_ReservationDocumentItem|Reservation Document\nAPI_RESPYM_TEAM_CONFIG_SRV_0001|v4|/sap/opu/odata4/sap/api_respymgmt_config_srv/srvd_a2x/sap/api_respym_team_config_srv/0001|1|TeamCategories|TeamCategoryFunctions|Responsibility Management Team Configurations - Read\nAPI_RESPYM_TEAM_SRV_0001|v4|/sap/opu/odata4/sap/api_respymgmt_team_srv/srvd_a2x/sap/api_respym_team_srv/0001|1|TeamAttributes|TeamCategories|Responsibility Management Teams - Read\nAPI_RFQ_PROCESS_SRV|v2|/sap/opu/odata/sap/API_RFQ_PROCESS_SRV|0|A_RequestForQuotation|A_RequestForQuotationBidder|Request for Quotation\nAPI_SALESDISTRICT_SRV|v2|/sap/opu/odata/sap/API_SALESDISTRICT_SRV|0|A_SalesDistrict|A_SalesDistrictText|Sales District - Read\nAPI_SALESORGANIZATION_SRV|v2|/sap/opu/odata/sap/API_SALESORGANIZATION_SRV|0|A_SalesOrganization|A_SalesOrganizationText|Sales Organization - Read\nAPI_SALES_CONTRACT_SRV|v2|/sap/opu/odata/sap/API_SALES_CONTRACT_SRV|0|A_SalesContract|A_SalesContractItem|Sales Contract (A2X)\nAPI_SALES_DOCUMENT_REASON_SRV|v2|/sap/opu/odata/sap/API_SALES_DOCUMENT_REASON_SRV|0|A_RetroBillingUsageText|A_SDDocumentReason|SD Document Order Reason - Read (A2X)\nAPI_SALES_INQUIRY_SRV|v2|/sap/opu/odata/sap/API_SALES_INQUIRY_SRV|0|A_SalesInquiry|A_SalesInquiryItem|Sales Inquiry - Read (A2X)\nAPI_SALES_ORDER_SIMULATION_SRV|v2|/sap/opu/odata/sap/API_SALES_ORDER_SIMULATION_SRV|0|A_SalesOrderCreditSimulation|A_SalesOrderItemPartnerSimln|Sales Order - Simulate (A2X)\nAPI_SALES_ORDER_SRV|v2|/sap/opu/odata/sap/API_SALES_ORDER_SRV|0|A_SalesOrder|A_SalesOrderBillingPlan|Sales Order (A2X)\nAPI_SALES_ORDER_WITHOUT_CHARGE_SRV|v2|/sap/opu/odata/sap/API_SALES_ORDER_WITHOUT_CHARGE_SRV|0|A_SalesOrderWithoutCharge|A_SalesOrderWithoutChargeItem|Sales Order Without Charge (A2X)\nAPI_SALES_QUOTATION_SRV|v2|/sap/opu/odata/sap/API_SALES_QUOTATION_SRV|0|A_SalesQuotation|A_SalesQuotationItem|Sales Quotation (A2X)\nAPI_SALES_SCHEDULING_AGREEMENT|v2|/sap/opu/odata/sap/API_SALES_SCHEDULING_AGREEMENT|0|A_SalesSchedgAgrmt|A_SalesSchedgAgrmtDelivSched|Sales Scheduling Agreement (A2X)\nAPI_SCHED_AGRMT_PROCESS_SRV|v2|/sap/opu/odata/sap/API_SCHED_AGRMT_PROCESS_SRV|0|A_SchAgrmtAcCnt|A_SchAgrmtHeader|Scheduling Agreements\nAPI_SD_INCOTERMS_SRV|v2|/sap/opu/odata/sap/API_SD_INCOTERMS_SRV|0|A_IncotermsClassification|A_IncotermsClassificationText|Incoterm - Read (A2X)\nAPI_SD_SA_SOLDTOPARTYDETN|v2|/sap/opu/odata/sap/API_SD_SA_SOLDTOPARTYDETN|0|A_DelivSchedSoldToPartyDetn||Sold-to Party Assignment of Sales Scheduling Agreement - Read (A2X)\nAPI_SEGMENT_SRV|v2|/sap/opu/odata/sap/API_SEGMENT_SRV|0|A_Segment|A_SegmentText|Segment - Read\nAPI_SERVICE_CONFIRMATION_SRV|v2|/sap/opu/odata/sap/API_SERVICE_CONFIRMATION_SRV|0|A_ServiceConfirmation|A_ServiceConfirmationItem|Service Confirmation (A2X)\nAPI_SERVICE_CONTRACT_SRV|v2|/sap/opu/odata/sap/API_SERVICE_CONTRACT_SRV|0|A_ServiceContract|A_ServiceContractItem|Service Contract - Read (A2X)\nAPI_SERVICE_ENTRY_SHEET_SRV|v2|/sap/opu/odata/sap/API_SERVICE_ENTRY_SHEET_SRV|0|A_ServiceEntrySheet|A_ServiceEntrySheetItem|Service Entry Sheet (Lean Services)\nAPI_SERVICE_ORDER_SRV|v2|/sap/opu/odata/sap/API_SERVICE_ORDER_SRV|0|A_ServiceOrder|A_ServiceOrderConfirmation|Service Order (A2X)\nAPI_SERVICE_ORDER_TEMPLATE_SRV|v2|/sap/opu/odata/sap/API_SERVICE_ORDER_TEMPLATE_SRV|0|A_ServiceOrderTemplate|A_ServiceOrderTemplateItem|Service Order Template\nAPI_SERVICE_QUOTATION_SRV_0002|v2|/sap/opu/odata/sap/API_SERVICE_QUOTATION_SRV|0|A_ServiceQtanItemPriceElement|A_ServiceQtanItemRefObject|Service Quotation (A2X)\nAPI_SLSPRICINGCONDITIONRECORD_SRV|v2|/sap/opu/odata/sap/API_SLSPRICINGCONDITIONRECORD_SRV|0|A_SlsPrcgCndnRecdSuplmnt|A_SlsPrcgCndnRecdValidity|Condition Record for Pricing in Sales\nAPI_SLSPRICINGCONDITIONTYPE_SRV|v2|/sap/opu/odata/sap/API_SLSPRICINGCONDITIONTYPE_SRV|0|A_SlsPrcgCndnTypeText|A_SlsPrcgConditionFunctionText|Condition Type for Pricing in Sales – Read\nAPI_SLSPRICINGPROCEDURE_SRV|v2|/sap/opu/odata/sap/API_SLSPRICINGPROCEDURE_SRV|0|A_SlsPrcgCndnTypeText|A_SlsPrcgProcedItemText|Pricing Procedure in Sales - Read\nAPI_SLS_DOC_WITH_CREDIT_BLOCK|v2|/sap/opu/odata/sap/API_SLS_DOC_WITH_CREDIT_BLOCK|0|A_CreditBlockedSalesDocument|A_SalesDocumentRjcnReason|Sales Document with Credit Block - Read, Check, Release, Reject (A2X)\nAPI_STATISTICALKEYFIGURE_SRV|v2|/sap/opu/odata/sap/API_STATISTICALKEYFIGURE_SRV|0|A_StatisticalKeyFigure|A_StatisticalKeyFigureText|Statistical Key Figure (A2X)\nAPI_SUBSQNT_BILLG_DOC_SBI_SRV|v2|/sap/opu/odata/sap/API_SUBSQNT_BILLG_DOC_SBI_SRV|0|A_SubsqntBillgDocForSelfBillg||Billing Document - Read Subsequent Billing Documents\nAPI_SUPAVAILYPROTPLAN_0001|v4|/sap/opu/odata4/sap/api_supavailyprotplan/srvd_a2x/sap/api_supavailyprotplan/0001|0|A_SupplyProtection|A_SupplyProtectionGroup|Supply Availability Protection Plan\nAPI_SUPAVAILYPROTPLAN_0002|v4|/sap/opu/odata4/sap/api_supavailyprotplan/srvd_a2x/sap/api_supavailyprotplan/0002|0|A_SupplyProtection|A_SupplyProtectionGroup|Supply Availability Protection Plan\nAPI_SUPLR_EVAL_RESPONSE_SRV|v2|/sap/opu/odata/sap/API_SUPLR_EVAL_RESPONSE_SRV|0|A_SuplrEvalRspAppraiser|A_SuplrEvalRspExplText|Supplier Evaluation Response - Read\nAPI_SUPLR_EVAL_SCORECARD_SRV|v2|/sap/opu/odata/sap/API_SUPLR_EVAL_SCORECARD_SRV|0|A_SuplrEvalSccrdPurchaserResp|A_SuplrEvalSccrdQuestion|Supplier Evaluation Scorecard - Read\nAPI_SUPPLIERINVOICE_PROCESS_SRV|v2|/sap/opu/odata/sap/API_SUPPLIERINVOICE_PROCESS_SRV|0|A_BR_SupplierInvoiceNFDocument|A_SuplrInvcHeaderWhldgTax|Supplier Invoice\nAPI_SUPPLIER_ACTIVITY_SRV|v2|/sap/opu/odata/sap/API_SUPPLIER_ACTIVITY_SRV|0|A_SuplrActyDescription|A_SuplrActyParticipant|Procurement-Related Activity\nAPI_SUPPLIER_ACTIVITY_TASK_SRV|v2|/sap/opu/odata/sap/API_SUPPLIER_ACTIVITY_TASK_SRV|0|A_SuplrActyTskActyReference|A_SuplrActyTskCommText|Procurement-Related Task\nAPI_TRSYPOSFLOW_SRV|v2|/sap/opu/odata/sap/A_TRSYPOSFLOW_CDS|0|A_TrsyPosFlow||Treasury Position Flow – Read\nAPI_TRSYPOSTGJRNLENTRITM_SRV|v2|/sap/opu/odata/sap/A_TRSYPOSTGJRNLENTRITM_CDS|0|A_TrsyPostgJrnlEntrItm||Line Item of Treasury Posting Journal Entry – Read\nAPI_WORK_CENTERS|v2|/sap/opu/odata/sap/API_WORK_CENTERS|0|A_WorkCenterAllCapacity|A_WorkCenterAllCapacity_2|Work Center\nA_LGLCNTNTMACCESSLVL_CDS|v2|/sap/opu/odata/sap/A_LGLCNTNTMACCESSLVL_CDS|0|A_LglCntntMAccessLvl|A_LglCntntMAccessLvlText|Legal Document Access Levels - Read\nA_SUPPLIEROPLSCORESAV_CDS|v2|/sap/opu/odata/sap/A_SUPPLIEROPLSCORESAV_CDS|0|A_SupplierOplScoresAVResults|A_SupplierOplScoresAV|Supplier Evaluation Operational Score - Read\nBANK_0001|v4|/sap/opu/odata4/sap/api_bank/srvd_a2x/sap/bank/0001|1|Bank||Bank\nBANK_0002|v4|/sap/opu/odata4/sap/api_bank/srvd_a2x/sap/bank/0002|1|Bank|BankAddress|Bank\nCABILLABLEITEM_0001|v4|/sap/opu/odata4/sap/api_cabillableitem/srvd_a2x/sap/cabillableitem/0001|0|CABllbleItmDataPackage|CABllbleItmMain|Convergent Invoicing Billable Item\nCABILLINGDOCUMENT_0001|v4|/sap/opu/odata4/sap/api_cabillingdocument/srvd_a2x/sap/cabillingdocument/0001|0|CABillgDocItem|CABillgDocSource|Convergent Invoicing Billling Document - Read\nCABILLINGPLAN_0001|v4|/sap/opu/odata4/sap/api_cabillingplan/srvd_a2x/sap/cabillingplan/0001|0|CABillgPln|CABillgPlnItem|Convergent Invoicing Billing Plan\nCABILLINGREQUEST_0001|v4|/sap/opu/odata4/sap/api_cabillingrequest/srvd_a2x/sap/cabillingrequest/0001|0|CABillgRequest|CABillgRequestItem|Convergent Invoicing Billing Request\nCABUSINESSTRANSACTION_0001|v4|/sap/opu/odata4/sap/api_cabusinesstransaction/srvd_a2x/sap/cabusinesstransaction/0001|0|BusinessTransaction||Contract Accounting Business Transaction - Read\nCACONSUMPTIONITEM_0001|v4|/sap/opu/odata4/sap/api_caconsumptionitem/srvd_a2x/sap/caconsumptionitem/0001|0|CAConsumptionItem||Convergent Invoicing Consumption Item - Read\nCACREDITWORTHINESS_0001|v4|/sap/opu/odata4/sap/api_cacreditworthiness/srvd_a2x/sap/cacreditworthiness/0001|0|CreditWorthiness||Contract Accounting Creditworthiness – Read\nCADISPUTECASE_0001|v4|/sap/opu/odata4/sap/api_cadisputecase/srvd_a2x/sap/cadisputecase/0001|1|DisputeCase|DisputeCaseObject|Contract Accounting Dispute Case – Manage\nCADOCUMENTMANAGE_0001|v4|/sap/opu/odata4/sap/api_cadocumentmanage/srvd_a2x/sap/cadocumentmanage/0001|0|BPItem|BPItemBusLock|Contract Accounting Document – Manage\nCADUNNING_0001|v4|/sap/opu/odata4/sap/api_cadunning/srvd_a2x/sap/cadunning/0001|0|CADunning|CADunningItem|Contract Accounting Dunning - Read\nCAINVCGCLRFCTNCASE_0001|v4|/sap/opu/odata4/sap/api_cainvcgclrfctncase/srvd_a2x/sap/cainvcgclrfctncase/0001|0|CAInvcgClrfctnCase||Convergent Invoicing Clarification Case\nCAINVOICINGDOCUMENT_0001|v4|/sap/opu/odata4/sap/api_cainvoicingdocument/srvd_a2x/sap/cainvoicingdocument/0001|0|CAInvcgDocChargeAndDiscount|CAInvcgDocChrgAndDiscHistory|Convergent Invoicing Invoicing Document - Read\nCATAXDETERMINATIONCODE_0001|v4|/sap/opu/odata4/sap/api_cataxdeterminationcode/srvd_a2x/sap/cataxdeterminationcode/0001|0|ContrAcctgTaxDeterminationCode||Contract Accounting Tax Determination Code\nCA_BEH_SUBSCRIPTION_SRV|v2|/sap/opu/odata/sap/CA_BEH_SUBSCRIPTION_SRV|0|SubscriptionMaintain|SubscriptionRead|Business Events Subscription\nCENTRALREQUESTFORQUOTATION_0001|v4|/sap/opu/odata4/sap/api_cntrlreqforquotation/srvd_a2x/sap/centralrequestforquotation/0001|0|CentralReqForQuotationItem|CentralRequestForQuotation|Central Request for Quotation\nCENTRALSUPPLIERQUOTATION_0001|v4|/sap/opu/odata4/sap/api_cntrlsupplierquotation/srvd_a2x/sap/centralsupplierquotation/0001|0|CentralSupplierQuotation|CntrlSuplrQuotationItemDistr|Central Supplier Quotation\nCE_ABOPRUN_0001|v4|/sap/opu/odata4/sap/api_aboprun/srvd_a2x/sap/aboprun/0001|0|AdvancedBackorderProcessingRun||Advanced Backorder Processing Run\nCE_APIAVAILTOPROMISECHECK_0001|v4|/sap/opu/odata4/sap/api_avail_to_promise_check/srvd_a2x/sap/apiavailtopromisecheck/0001|0|RlvtProductPlant||Advanced ATP Check\nCE_APIRESERVATIONDOCUMENT_0001|v4|/sap/opu/odata4/sap/api_reservation_document/srvd_a2x/sap/apireservationdocument/0001|0|ReservationDocument|ReservationDocumentItem|Reservation Document (A2X)\nCE_API_BUS_SITN_MSTRDATA_SRV_0002|v2|/sap/opu/odata/sap/API_BUS_SITN_MSTRDATA_SRV|0|SituationAction|SituationActionText|Business Situation Type - Read\nCE_API_CNSLDTNGRPJRNLITEM_0001|v2|/sap/opu/odata/sap/API_CNSLDTNGRPJRNLITEM|0|CnsldtnGrpJrnlItemResults|CnsldtnGrpJrnlItem|Consolidation Group Journal Entry\nCE_API_CREDIT_MEMO_REQ_SIMLN_SRV_0001|v2|/sap/opu/odata/sap/API_CREDIT_MEMO_REQ_SIMULATION_SRV|0|A_CrdtMemoReqItemPartnerSimln|A_CrdtMemoReqItmPrcgElmntSimln|Credit Memo Request - Simulate (A2X)\nCE_API_CUSTRET_SIMULATION_SRV_0001|v2|/sap/opu/odata/sap/API_CUSTOMER_RETURN_SIMULATION_SRV|0|A_CustomerReturnItemSimulation|A_CustomerReturnSimulation|Customer Return - Simulate (A2X)\nCE_API_DEBIT_MEMO_REQ_SIMLN_SRV_0001|v2|/sap/opu/odata/sap/API_DEBIT_MEMO_REQ_SIMULATION_SRV|0|A_DebitMemoReqCreditSimulation|A_DebitMemoReqItemPartnerSimln|Debit Memo Request - Simulate (A2X)\nCE_API_MAINTENANCEORDER_0002|v2|/sap/opu/odata/sap/API_MAINTENANCEORDER|0|MaintenanceOrder|MaintenanceOrderLongText|Maintenance Order\nCE_API_PRODUCTION_ROUTING_0002|v2|/sap/opu/odata/sap/API_PRODUCTION_ROUTING|1|OpInspCharcsAssgmt|ProductionRoutingOpCtrlPrflTxt|Production Routing\nCE_API_PRODUCT_AVAILY_INFO_0001|v4|/sap/opu/odata4/sap/api_product_availy_info/srvd_a2x/sap/apiproductavailyinfo/0001|0|ProductAssignedSalesDocItem|ProductAssignedSoldToParty|Product Availability Info\nCE_API_RESPYM_TEAM_CONFIG_SRV_0002|v4|/sap/opu/odata4/sap/api_respymgmt_config_srv/srvd_a2x/sap/api_respym_team_config_srv/0002|0|TeamCategories|TeamCategoryFunctions|Responsibility Management Team Configurations - Read\nCE_API_RESPYM_TEAM_SRV_0002|v4|/sap/opu/odata4/sap/api_respymgmt_team_srv/srvd_a2x/sap/api_respym_team_srv/0002|0|TeamAttributes|TeamCategories|Responsibility Management Teams - Read\nCE_API_RFM_ASSORTMENT_MODULE_0002|v4|/sap/opu/odata4/sap/api_rfm_assortment_module/srvd_a2x/sap/api_rfm_assortment_module/0002|0|AssortmentModuleVersionText|ExplctAsstmtMdlUsrProdExclsn|Assortment Module (A2X)\nCE_API_RFM_PROD_DC_LISTING_0001|v4|/sap/opu/odata4/sap/api_rfm_prod_dc_listing/srvd_a2x/sap/api_rfm_prod_dc_listing/0001|0|ProductDistrCtrListing||Product Assignment to Distribution Center (A2X)\nCE_API_STDWRKFMLAPARAM_GROUP_0001|v2|/sap/opu/odata/sap/API_STDWRKFMLAPARAM_GROUP|0|StandardWorkFmlaParamGroup|StandardWorkFmlaParamGrpText|Standard Work Formula Parameter Group ‒ Read\nCE_APS_IAM_API_BROLE_CDOC_0001|v2|/sap/opu/odata/sap/APS_IAM_API_BROLE_CDOC|0|BusinessRoleChanges||Business Role Changes - Read\nCE_APS_IAM_API_BUSER_CDOC_0001|v2|/sap/opu/odata/sap/APS_IAM_API_BUSER_CDOC|0|BusinessUserChanges||Business User Change - Read\nCE_BANK_0003|v4|/sap/opu/odata4/sap/api_bank/srvd_a2x/sap/bank/0003|0|Bank|BankAddress|Bank\nCE_CASECURITYDEPOSIT_0002|v4|/sap/opu/odata4/sap/api_casecuritydeposit/srvd_a2x/sap/casecuritydeposit/0002|0|CAScrtyDepDocumentFlow|CASecurityDeposit|Cash Security Deposit\nCE_CA_RSM_TEAM_MANAGE_0001|v2|/sap/opu/odata/sap/CA_RSM_TEAM_MANAGE|0|TeamMemberFunctionSet|TeamHierarchyNodeSet|Responsibility Management Teams\nCE_CHANGERECDLIFECYCLESTATUS_0001|v4|/sap/opu/odata4/sap/api_changerecdlifecyclestatus/srvd_a2x/sap/changerecdlifecyclestatus/0001|0|A_ChangeRecordLifeCycleStatus|A_ChgRecordLifeCycleStatusText|Change Record Lifecycle Status\nCE_CHANGERECORDHISTORY_IWSV_0001|v4|/sap/opu/odata4/sap/api_changerecordhistory/srvd_a2x/sap/changerecordhistory/0001|0|A_ChangeRecordHistory||Change Record - History\nCE_CHANGERECORDITEMRELEVANCE_0001|v4|/sap/opu/odata4/sap/api_changerecorditemrelevance/srvd_a2x/sap/changerecorditemrelevance/0001|1|A_ChangeRecordItemRelevance|A_ChgRecordItemRelevanceText|Change Record Possible Item Relevance\nCE_CHANGERECORDITEMRELEVANCE_1_0001|v4|/sap/opu/odata4/sap/api_changerecorditemrelevance/srvd_a2x/sap/changerecorditemrelevance/0002|0|ChangeRecordItemRelevance|ChgRecordItemRelevanceText|Change Record Possible Item relevance ‒ Read\nCE_CHEMICALCOMPLIANCEINFO_0001|v4|/sap/opu/odata4/sap/api_chemicalcomplianceinfo/srvd_a2x/sap/chemicalcomplianceinfo/0001|0|ChemicalComplianceInfo|ChmlCmplncInfoPckgdProduct|Chemical Compliance Info\nCE_CHGRECORDUSERSTATUS_0001|v4|/sap/opu/odata4/sap/api_chgrecorduserstatus/srvd_a2x/sap/chgrecorduserstatus/0001|0|A_ChgRecordUserStatus|A_ChgRecordUserStatusText|Change Record User Status\nCE_CNSLDTNBILLINGDOCUMENTTYPE_0001|v4|/sap/opu/odata4/sap/api_cnsldtnbillingdoctype/srvd_a2x/sap/cnsldtnbillingdocumenttype/0001|0|CnsldtnBillingDocumentType|CnsldtnBillingDocumentTypeText|Consolidation Billing Document Type\nCE_CNSLDTNBUSINESSAREA_0001|v4|/sap/opu/odata4/sap/api_cnsldtnbusinessarea/srvd_a2x/sap/cnsldtnbusinessarea/0001|0|CnsldtnBusinessArea|CnsldtnBusinessAreaText|Consolidation Business Area\nCE_CNSLDTNCHARTOFACCOUNTS_0001|v4|/sap/opu/odata4/sap/api_cnsldtnchartofaccounts/srvd_a2x/sap/cnsldtnchartofaccounts/0001|0|CnsldtnChartOfAccountsText|ConsolidationChartOfAccounts|Consolidation Chart of Accounts - Read\nCE_CNSLDTNCONTROLLINGAREA_0001|v4|/sap/opu/odata4/sap/api_cnsldtncontrollingarea/srvd_a2x/sap/cnsldtncontrollingarea/0001|0|CnsldtnControllingArea|CnsldtnControllingAreaText|Consolidation Controlling Area\nCE_CNSLDTNCOSTCENTER_0001|v4|/sap/opu/odata4/sap/api_cnsldtncostcenter/srvd_a2x/sap/cnsldtncostcenter/0001|0|CnsldtnCostCenter|CnsldtnCostCenterText|Consolidation Cost Center\nCE_CNSLDTNCOUNTRY_0001|v4|/sap/opu/odata4/sap/api_cnsldtncountry/srvd_a2x/sap/cnsldtncountry/0001|0|CnsldtnCountry|CnsldtnCountryText|Consolidation Country/Region\nCE_CNSLDTNCUSTOMERGROUP_0001|v4|/sap/opu/odata4/sap/api_cnsldtncustomergroup/srvd_a2x/sap/cnsldtncustomergroup/0001|0|CnsldtnCustomerGroup|CnsldtnCustomerGroupText|Consolidation Customer Group\nCE_CNSLDTNCUSTOMER_0001|v4|/sap/opu/odata4/sap/api_cnsldtncustomer/srvd_a2x/sap/cnsldtncustomer/0001|0|CnsldtnCustomer|CnsldtnCustomerText|Consolidation Customer\nCE_CNSLDTNDISTRIBUTIONCHANNEL_0001|v4|/sap/opu/odata4/sap/api_cnsldtndistrchannel/srvd_a2x/sap/cnsldtndistributionchannel/0001|0|CnsldtnDistributionChannel|CnsldtnDistributionChannelText|Consolidation Distribution Channel\nCE_CNSLDTNDIVISION_0001|v4|/sap/opu/odata4/sap/api_cnsldtndivision/srvd_a2x/sap/cnsldtndivision/0001|0|CnsldtnDivision|CnsldtnDivisionText|Consolidation Division\nCE_CNSLDTNFINTRANSACTIONTYPE_0001|v4|/sap/opu/odata4/sap/api_cnsldtnfintranstype/srvd_a2x/sap/cnsldtnfintransactiontype/0001|0|CnsldtnFinTransactionType|CnsldtnFinTransactionTypeText|Consolidation Financial Transaction Type\nCE_CNSLDTNFSITEMHIERARCHY_0001|v4|/sap/opu/odata4/sap/api_cnsldtnfsitemhierarchy/srvd_a2x/sap/cnsldtnfsitemhierarchy/0001|0|CnsldtnFSItemHierarchyNode|CnsldtnFSItemHierarchyNodeText|Consolidation FS Item Hierarchy - Read\nCE_CNSLDTNFSITEM_0001|v4|/sap/opu/odata4/sap/api_cnsldtnfsitem/srvd_a2x/sap/cnsldtnfsitem/0001|0|CnsldtnFinancialStatementItem|CnsldtnFSItemByTimeVersion|Consolidation Financial Statement Item\nCE_CNSLDTNFUNCTIONALAREA_0001|v4|/sap/opu/odata4/sap/api_cnsldtnfunctionalarea/srvd_a2x/sap/cnsldtnfunctionalarea/0001|0|CnsldtnFunctionalArea|CnsldtnFunctionalAreaText|Consolidation Functional Area\nCE_CNSLDTNGLACCOUNT_0001|v4|/sap/opu/odata4/sap/api_cnsldtnglaccount/srvd_a2x/sap/cnsldtnglaccount/0001|0|CnsldtnGLAccount|CnsldtnGLAccountText|Consolidation G/L Account\nCE_CNSLDTNGLCHARTOFACCOUNTS_0001|v4|/sap/opu/odata4/sap/api_cnsldtnglchartofaccts/srvd_a2x/sap/cnsldtnglchartofaccounts/0001|0|CnsldtnGLChartOfAccounts|CnsldtnGLChartOfAcctsText|Consolidation G/L Chart of Accounts\nCE_CNSLDTNGROUPJOURNALENTRY_0001|v4|/sap/opu/odata4/sap/api_cnsldtngrpjrnlentr/srvd_a2x/sap/cnsldtngroupjournalentry/0001|0|CnsldtnGroupJournalEntry||Journal Entry for Group Reporting - Post\nCE_CNSLDTNGROUPSTRUCTURE_0001|v4|/sap/opu/odata4/sap/api_cnsldtngroupstructure/srvd_a2x/sap/cnsldtngroupstructure/0001|0|CnsldtnGrpStrucMethAssgmt|ConsolidationGroupStructure|Consolidation Group Structure\nCE_CNSLDTNINDUSTRY_0001|v4|/sap/opu/odata4/sap/api_cnsldtnindustry/srvd_a2x/sap/cnsldtnindustry/0001|0|CnsldtnIndustry|CnsldtnIndustryText|Consolidation Industry\nCE_CNSLDTNORDER_0001|v4|/sap/opu/odata4/sap/api_cnsldtnorder/srvd_a2x/sap/cnsldtnorder/0001|0|CnsldtnOrder|CnsldtnOrderText|Consolidation Order\nCE_CNSLDTNPLANT_0001|v4|/sap/opu/odata4/sap/api_cnsldtnplant/srvd_a2x/sap/cnsldtnplant/0001|0|CnsldtnPlant|CnsldtnPlantText|Consolidation Plant\nCE_CNSLDTNPRODUCTGROUP_0001|v4|/sap/opu/odata4/sap/api_cnsldtnproductgroup/srvd_a2x/sap/cnsldtnproductgroup/0001|0|CnsldtnProductGroup|CnsldtnProductGroupText|Consolidation Product Group\nCE_CNSLDTNPRODUCT_0001|v4|/sap/opu/odata4/sap/api_cnsldtnproduct/srvd_a2x/sap/cnsldtnproduct/0001|0|CnsldtnProduct|CnsldtnProductText|Consolidation Product\nCE_CNSLDTNPROFITCENTER_0001|v4|/sap/opu/odata4/sap/api_cnsldtnprofitcenter/srvd_a2x/sap/cnsldtnprofitcenter/0001|0|CnsldtnProfitCenter|CnsldtnProfitCenterText|Consolidation Profit Center\nCE_CNSLDTNSALESDISTRICT_0001|v4|/sap/opu/odata4/sap/api_cnsldtnsalesdistrict/srvd_a2x/sap/cnsldtnsalesdistrict/0001|0|CnsldtnSalesDistrict|CnsldtnSalesDistrictText|Consolidation Sales District\nCE_CNSLDTNSALESORGANIZATION_0001|v4|/sap/opu/odata4/sap/api_cnsldtnsalesorg/srvd_a2x/sap/cnsldtnsalesorganization/0001|0|CnsldtnSalesOrganization|CnsldtnSalesOrganizationText|Consolidation Sales Organization\nCE_CNSLDTNSEGMENT_0001|v4|/sap/opu/odata4/sap/api_cnsldtnsegment/srvd_a2x/sap/cnsldtnsegment/0001|0|CnsldtnSegment|CnsldtnSegmentText|Consolidation Segment\nCE_CNSLDTNSUPPLIER_0001|v4|/sap/opu/odata4/sap/api_cnsldtnsupplier/srvd_a2x/sap/cnsldtnsupplier/0001|0|CnsldtnSupplier|CnsldtnSupplierText|Consolidation Supplier\nCE_CNSLDTNTASKGROUPASSIGNMENT_0001|v4|/sap/opu/odata4/sap/api_cnsldtntaskgroupassignment/srvd_a2x/sap/cnsldtntaskgroupassignment/0001|0|CnsldtnTaskGroupAssignment||Consolidation Task Group Assignment - Read\nCE_CNSLDTNUNITDATACOLLECTION_0001|v4|/sap/opu/odata4/sap/api_cnsldtnunitdatacoll/srvd_a2x/sap/cnsldtnunitdatacollection/0001|0|CnsldtnUnitDataCollection|CnsldtnUnitDataCollectionText|Consolidation Unit Data Collection\nCE_COMPANYSUBSTANCE_0001|v4|/sap/opu/odata4/sap/api_companysubstance/srvd_a2x/sap/companysubstance/0001|0|CompanySubstance|CompanySubstanceText|Company Substance\nCE_CONSOLIDATIONTASKGROUP_0001|v4|/sap/opu/odata4/sap/api_consolidationtaskgroup/srvd_a2x/sap/consolidationtaskgroup/0001|0|CnsldtnTaskGroupTaskAssignment|ConsolidationTaskGroup|Consolidation Task Group - Read\nCE_CONSOLIDATIONTASK_0001|v4|/sap/opu/odata4/sap/api_consolidationtask/srvd_a2x/sap/consolidationtask/0001|0|CnsldtnTskDocTypeDtaCollection|ConsolidationTask|Consolidation Task ‒ Read\nCE_CONSOLIDATIONUNITTASKRUN_0001|v4|/sap/opu/odata4/sap/api_consolidationunittaskrun/srvd_a2x/sap/consolidationunittaskrun/0001|0|ConsolidationUnitTaskRun||Consolidation Unit Task Run\nCE_CONSOLIDATIONUNITTASKRUN_0002|v4|/sap/opu/odata4/sap/api_consolidationunittaskrun/srvd_a2x/sap/consolidationunittaskrun/0002|0|ConsolidationUnitTaskRun||Consolidation Unit Task Run\nCE_COSTCENTER_0001|v4|/sap/opu/odata4/sap/api_cost_center/srvd_a2x/sap/costcenter/0001|0|A_CostCenterText_2|A_CostCenter_2|Cost Center\nCE_COSTRATE_0001|v4|/sap/opu/odata4/sap/api_cost_rate/srvd_a2x/sap/costrate/0001|0|ActualCostRate|PlanCostRate|Cost Rate\nCE_DIRECTACTIVITYALLOCATION_0001|v4|/sap/opu/odata4/sap/api_drctactivityallocation/srvd_a2x/sap/directactivityallocation/0001|0|ActivityAllocation|ActivityAllocationItem|Accounting Activity Allocation – Read, Create\nCE_ENTPROJELMNTDLVBRLTYPE_0001|v4|/sap/opu/odata4/sap/api_entprojelmntdlvbrltype/srvd_a2x/sap/entprojelmntdlvbrltype/0001|0|EntProjElmntDlvbrlType|EntProjElmntDlvbrlTypeText|Enterprise Project - Read Deliverable Type\nCE_FINANCIALTRANSACTIONNPV_0001|v4|/sap/opu/odata4/sap/api_fintransactionnpv/srvd_a2x/sap/financialtransactionnpv/0001|0|FinancialTransactionNPV||Financial Transaction Net Present Value\nCE_FIXEDASSETACQUISITION_0001|v4|/sap/opu/odata4/sap/api_fixedassetacquisition/srvd_a2x/sap/fixedassetacquisition/0001|0|FixedAssetAcquisition|FixedAssetAcquisitionItmAmount|Fixed Asset – Post Asset Acquisition\nCE_FIXEDASSETRETIREMENT_0001|v4|/sap/opu/odata4/sap/api_fixedassetretirement/srvd_a2x/sap/fixedassetretirement/0001|0|FixedAssetRetirement|FixedAssetRetirementLedger|Fixed Asset – Post Asset Retirement\nCE_FLDLOGSSHIPMENTCONTAINER_0001|v4|/sap/opu/odata4/sap/api_fldlogsshipmentcontainer/srvd_a2x/sap/fldlogsshipmentcontainer/0001|0|FldLogsShipmentContainer|FldLogsShipmentContainerCert|Field Logistics - Shipment Container\nCE_FREIGHTAGREEMENT_0001|v4|/sap/opu/odata4/sap/api_transpfreightagreement/srvd_a2x/sap/freightagreement/0001|0|FreightAgreement|FreightAgreementItem|Freight Agreement (A2X)\nCE_FREIGHTBOOKING_0001|v4|/sap/opu/odata4/sap/api_freightbooking/srvd_a2x/sap/freightbooking/0001|0|FreightBooking|FreightBookingBusinessPartner|Freight Booking (A2X)\nCE_FREIGHTORDER_0001|v4|/sap/opu/odata4/sap/api_freightorder/srvd_a2x/sap/freightorder/0001|0|FreightOrder|FreightOrderBusinessPartner|Freight Order (A2X)\nCE_FREIGHTUNIT_0001|v4|/sap/opu/odata4/sap/api_freightunit/srvd_a2x/sap/freightunit/0001|0|FreightUnit|FreightUnitBusinessPartner|Freight Unit (A2X)\nCE_LABELTEMPLATEMETADATA_0001|v4|/sap/opu/odata4/sap/api_pclbltemplatemetadata/srvd_a2x/sap/labeltemplatemetadata/0001|0|LabelTemplate|LabelTemplateVersion|Label Template Metadata\nCE_LEGALCONTEXT_0001|v4|/sap/opu/odata4/sap/api_legalcontext/srvd_a2x/sap/legalcontext/0001|0|LegalContext|LegalContextCategory|Legal Context - Read\nCE_MANAGELOCATION_0001|v4|/sap/opu/odata4/sap/api_managelocation/srvd_a2x/sap/managelocation/0001|0|Location|LocationAddress|Location\nCE_MERCHANDISECATEGORY_0001|v4|/sap/opu/odata4/sap/api_merchandisecategory/srvd_a2x/sap/merchandisecategory/0001|1|MerchandiseCategory|MerchandiseCategoryText|Merchandise Category (A2X)\nCE_MRCHDSCATHIERARCHYNODE_0001|v4|/sap/opu/odata4/sap/api_mrchdscathiernode/srvd_a2x/sap/mrchdscathierarchynode/0001|1|MCHierNodeCVRstrn|MrchdsCategoryHierarchyNode|Merchandise Category Hierarchy Node (A2X)\nCE_PHYSICALCHEMICALPROPERTY_0001|v4|/sap/opu/odata4/sap/api_physicalchemicalprpty/srvd_a2x/sap/physicalchemicalproperty/0001|0|PhysChmlBoilingPoint|PhysChmlBulkDensity|Physical-Chemical Properties\nCE_PLANTEXCLUSION_0002|v4|/sap/opu/odata4/sap/api_plantsubstnexclsn/srvd_a2x/sap/plantexclusion/0002|0|PlantExclusion||Plant Substitution Exclusion\nCE_PLANTSUBSTITUTION_0002|v4|/sap/opu/odata4/sap/api_plantsubstn/srvd_a2x/sap/plantsubstitution/0002|0|PlantSubstitution||Plant Substitution\nCE_POISONCENTERNOTIFICATION_0001|v4|/sap/opu/odata4/sap/api_poisoncenternotification/srvd_a2x/sap/poisoncenternotification/0001|0|PCNCountry|PCNDetail|Poison Center Notification\nCE_POLYMERCOMPOSITION_0001|v4|/sap/opu/odata4/sap/api_polymercomposition/srvd_a2x/sap/polymercomposition/0001|0|PolymerComponent|PolymerComposition|Polymer Composition\nCE_PRACONTRACTMARKETING_0001|v4|/sap/opu/odata4/sap/api_pracontractmarketing/srvd_a2x/sap/pracontractmarketing/0001|0|ContrMarketingDet|ContrMarketingHdr|PRA Contract Marketing Cost\nCE_PRAINTERNALMARKETING_0001|v4|/sap/opu/odata4/sap/api_prainternalmarketing/srvd_a2x/sap/prainternalmarketing/0001|0|PRAInternalMarketingRate|PRAInternalMarketingRateTrans|PRA Internal Marketing Rate\nCE_PRAMEASUREMENTPOINTVOL_0001|v4|/sap/opu/odata4/sap/api_prameasurementpointvol/srvd_a2x/sap/prameasurementpointvol/0001|0|MeasurementItems|MeasurementPointVolume|PRA Measurement Point Volume\n'
}
private static String svcChunk1() {
    return 'CE_PRAREVENUEACCTDOCUMENT_0001|v4|/sap/opu/odata4/sap/api_prarevenueacctdocument/srvd_a2x/sap/prarevenueacctdocument/0001|0|PRARevenueAccountingHeader|PRARevenueAcctItems|PRA Revenue Accounting Document - Post/Simulate/Read\nCE_PRAWELLCOMPLETIONDOWNTIME_0001|v4|/sap/opu/odata4/sap/api_prawellcompletiondowntime/srvd_a2x/sap/prawellcompletiondowntime/0001|0|WellCompltnDwnTme|WellCompltnDwnTmeReason|PRA WellCompletion Downtime\nCE_PRAWELLCOMPLETIONVOLUME_0001|v4|/sap/opu/odata4/sap/api_prawellcompletionvol/srvd_a2x/sap/prawellcompletionvolume/0001|0|MeasurementItems|WellCompletionVolume|PRA WellCompletion Volume\nCE_PRAWELLCOMPLTNPRSSR_0001|v4|/sap/opu/odata4/sap/api_prawellcompltnprssr/srvd_a2x/sap/prawellcompltnprssr/0001|0|WellCompletionDailyPressure|WellCompletionPressureReadings|PRA WellCompletion Daily Pressure\nCE_PRAWELLTEST_0001|v4|/sap/opu/odata4/sap/api_prawelltest/srvd_a2x/sap/prawelltest/0001|0|WellTest|WellTestReadings|PRA Well Test\nCE_PRODANALYTICALCOMPOSITION_0001|v4|/sap/opu/odata4/sap/api_prodanalyticalcmpstn/srvd_a2x/sap/prodanalyticalcomposition/0001|0|ProductAnalyticalComponent|ProductAnalyticalComposition|Product Analytical Composition\nCE_PRODMATERIALBSDCOMPOSITION_0001|v4|/sap/opu/odata4/sap/api_prodmatlbsdcomposition/srvd_a2x/sap/prodmaterialbsdcomposition/0001|0|ProdMatlBsdAfterProdnComp|ProdMatlBsdBeforeProdnComp|Product Material-Based Composition\nCE_PRODUCTEXCLUSION_0002|v4|/sap/opu/odata4/sap/api_prodsubstnexclsn/srvd_a2x/sap/productexclusion/0002|1|ProductSubstitutionExclusion||Product Substitution Exclusion\nCE_PRODUCTEXCLUSION_0003|v4|/sap/opu/odata4/sap/api_prodsubstnexclsn/srvd_a2x/sap/productexclusion/0003|0|ProductSubstitutionExclusion||Product Substitution Exclusion\nCE_PRODUCTIONVERSION_0001|v4|/sap/opu/odata4/sap/api_production_version/srvd_a2x/sap/productionversion/0001|0|ProductionVersion||Production Version\nCE_PRODUCTSUBSTITUTION_0002|v4|/sap/opu/odata4/sap/api_productsubstitution/srvd_a2x/sap/productsubstitution/0002|1|ProductSubstitution|ProductSubstitutionPredecessor|Product Substitution\nCE_PRODUCTSUBSTITUTION_0003|v4|/sap/opu/odata4/sap/api_productsubstitution/srvd_a2x/sap/productsubstitution/0003|0|ProductSubstitution|ProductSubstitutionPredecessor|Product Substitution\nCE_PROJDEMANDASSIGNMENTSTATUS_0001|v4|/sap/opu/odata4/sap/api_projdmndassgmtstatus/srvd_a2x/sap/projdemandassignmentstatus/0001|0|ProjDmndAssgmtStatus|ProjDmndAssgmtStatusText|Assignment Status for Project Demands - Read\nCE_PROJDEMANDLASTUPDATESOURCE_0001|v4|/sap/opu/odata4/sap/api_projdmndlastupdtsource/srvd_a2x/sap/projdemandlastupdatesource/0001|0|ProjDemandLastUpdateSourceText|ProjectDemandLastUpdateSource|Update Source for Project Demands – Read\nCE_PROJDEMANDSOURCEOFSUPPLY_0001|v4|/sap/opu/odata4/sap/api_projdmndsrceofsupply/srvd_a2x/sap/projdemandsourceofsupply/0001|0|ProjectDemandSourceOfSupply|ProjectDemandSourceOfSupplyTxt|Resource Assignment Source for Project Demands – Read\nCE_PROJECTDEMANDCATEGORY_0001|v4|/sap/opu/odata4/sap/api_projectdemandcategory/srvd_a2x/sap/projectdemandcategory/0001|0|ProjectDemandCategory|ProjectDemandCategoryText|Project Demand Category and Type - Read\nCE_PROJECTDEMANDSTATUS_0001|v4|/sap/opu/odata4/sap/api_projectdemandstatus/srvd_a2x/sap/projectdemandstatus/0001|0|ProjectDemandStatus|ProjectDemandStatusText|Project Demand Status - Read\nCE_PROJECTSERVICEORGANIZATION_0001|v4|/sap/opu/odata4/sap/api_serviceorganization/srvd_a2x/sap/projectserviceorganization/0001|0|CostCenter|ServiceOrganization|Enterprise Project Service Organization\nCE_PURCHASEORDER_0001|v4|/sap/opu/odata4/sap/api_purchaseorder_2/srvd_a2x/sap/purchaseorder/0001|0|POSubcontractingComponent|PurchaseOrder|Purchase Order\nCE_PURCHASEREQUISITION_0001|v4|/sap/opu/odata4/sap/api_purchaserequisition_2/srvd_a2x/sap/purchaserequisition/0001|0|PurchaseReqn|PurchaseReqnAcctAssgmt|Purchase Requisition\nCE_PURCHASINGGROUP_0001|v4|/sap/opu/odata4/sap/api_purchasinggroup/srvd_a2x/sap/purchasinggroup/0001|0|A_PurchasingGroup||Purchasing Group - Read\nCE_PURCHASINGORGANIZATION_0001|v4|/sap/opu/odata4/sap/api_purchasingorganization/srvd_a2x/sap/purchasingorganization/0001|0|A_PurchasingOrganization||Purchasing Organization - Read\nCE_PURORDACCRSACCRPERDCAMOUNT_0001|v4|/sap/opu/odata4/sap/api_purordaccrsaccrperdcamount/srvd_a2x/sap/purordaccrsaccrperdcamount/0001|0|PurOrdAccrsAccrPerdcAmount||Purchase Order Accruals Periodic Amount - Read, Adjust, Review\nCE_QUALITYNOTIFICATION_0001|v4|/sap/opu/odata4/sap/api_qualitynotification/srvd_a2x/sap/qualitynotification/0001|0|QltyNotificationItemLongText|QltyNotificationTaskLongText|Quality Notification - Read, Create\nCE_REOCCUPANCY_0001|v4|/sap/opu/odata4/sap/api_re_occupancy/srvd_a2x/sap/reoccupancy/0001|0|REOccupancy||Real Estate Occupancy\nCE_REQUESTFORQUOTATION_0001|v4|/sap/opu/odata4/sap/api_requestforquotation_2/srvd_a2x/sap/requestforquotation/0001|0|RequestForQuotation|RequestForQuotationBidder|Request for Quotation\nCE_RESPACEGROUPTYPE_0001|v4|/sap/opu/odata4/sap/api_re_spacegrouptype/srvd_a2x/sap/respacegrouptype/0001|0|RESpaceGroupType|RESpaceGroupTypeText|Real Estate Usage Enablement and Occupancy Group Type\nCE_RETURNSINSPECTION_0001|v4|/sap/opu/odata4/sap/api_returnsinspection_2/srvd_a2x/sap/returnsinspection/0001|0|ReturnsInspection|ReturnsInspectionItem|Returns Inspection (A2X)\nCE_SAFETYDATASHEETASSESSMENT_0001|v4|/sap/opu/odata4/sap/api_safetydatasheetassessment/srvd_a2x/sap/safetydatasheetassessment/0001|0|SafetyDataSheetAssessment|SafetyDataSheetAssessmentCntry|Safety Data Sheet Assessment\nCE_SAFETYRELATEDPROPERTY_0001|v4|/sap/opu/odata4/sap/api_safetyrelatedproperty/srvd_a2x/sap/safetyrelatedproperty/0001|0|PCSftyAsphyxiationHazard|PCSftyAutoIgnition|Safety-Related Properties\nCE_SALESORDER_0001|v4|/sap/opu/odata4/sap/api_salesorder/srvd_a2x/sap/salesorder/0001|0|SalesOrder|SalesOrderItem|Sales Order (A2X)\nCE_SECURITYCLASS_0001|v4|/sap/opu/odata4/sap/api_securityclass/srvd_a2x/sap/securityclass/0001|1|BondRdmptnSchedCalcParam|BondRdmptnSchedCndnFmla|Security Class\nCE_SERVICECONTRACT_0001|v4|/sap/opu/odata4/sap/api_servicecontract/srvd_a2x/sap/servicecontract/0001|0|ServiceContract|ServiceContractItem|Service Contract (A2X)\nCE_SRVCCONTRTEMPLATE_0001|v4|/sap/opu/odata4/sap/api_srvccontracttemplate/srvd_a2x/sap/servicecontracttemplate/0001|0|ServiceContractTemplate|ServiceContractTemplateItem|Service Contract Template ‒ Read\nCE_STATRYRPTCATDEFINITION_0001|v4|/sap/opu/odata4/sap/api_statryrptcatdefinition/srvd_a2x/sap/statryrptcatdefinition/0001|0|ActivityDef|CategoryDef|Statutory Reporting Category Definition ‒ Read\nCE_STATRYRPTRPTDEFINITION_0001|v4|/sap/opu/odata4/sap/api_statryrptrptdefinition/srvd_a2x/sap/statryrptrptdefinition/0001|0|DocumentDef|ReportDef|Statutory Reporting Report Definition ‒ Read\nCE_STATUTORYREPORTINGTASK_0001|v4|/sap/opu/odata4/sap/api_statutoryreportingtask/srvd_a2x/sap/statutoryreportingtask/0001|0|Activity|Phase|Statutory Reporting Task\nCE_STORAGELOCATIONEXCLUSION_0002|v4|/sap/opu/odata4/sap/api_storlocsubstnexclsn/srvd_a2x/sap/storagelocationexclusion/0002|0|StorageLocationExclusion||Storage Location Substitution Exclusion\nCE_STORLOCSUBSTITUTION_0002|v4|/sap/opu/odata4/sap/api_storlocsubstn/srvd_a2x/sap/storlocsubstitution/0002|0|StorageLocationSubstitution||Storage Location Substitution\nCE_SUBSTANCEVOLUME_0002|v4|/sap/opu/odata4/sap/api_svt/srvd_a2x/sap/substancevolume/0002|0|Manufacturing|Purchasing|Substance Volumes - Read\nCE_SUPPLIERCONFIRMATION_0001|v4|/sap/opu/odata4/sap/api_supplierconfirmation/srvd_a2x/sap/supplierconfirmation/0001|0|Confirmation|ConfirmationItem|Supplier Confirmation\nCE_SUPPLIERQUOTATION_0001|v4|/sap/opu/odata4/sap/api_supplierquotation_2/srvd_a2x/sap/supplierquotation/0001|0|SupplierQuotation|SupplierQuotationItem|Supplier Quotation\nCE_TRANSPORTATIONRATETABLE_0001|v4|/sap/opu/odata4/sap/api_transpratetable/srvd_a2x/sap/transportationratetable/0001|0|TransportationRateTable|TranspRateTableCalcRule|Rate Table (A2X)\nCE_VARCNFCHARCGROUP_0001|v4|/sap/opu/odata4/sap/api_varcnfcharcgroup/srvd_a2x/sap/varcnfcharacteristicgroup/0001|0|VarConfignCharacteristicGroup|VarConfignCharcGroupAlloc|Variant Configuration Characteristic Group\nCE_VARCONFIGNCONSTRAINTNET_0001|v4|/sap/opu/odata4/sap/api_varconfignconstraintnet/srvd_a2x/sap/varconfignconstraintnet/0001|0|VarCnfConstraint|VarCnfConstraintNet|Constraint Net\nCE_WASTETRANSPDOCS_0001|v4|/sap/opu/odata4/sap/api_transpdoc/srvd_a2x/sap/wastetranspdocs/0001|0|TranspDoc|TranspDocMatl|Waste Transportation Documents\nCE_WHSEFIXEDBINASSIGNMENT_0001|v4|/sap/opu/odata4/sap/api_whse_fixbin_assgnmnt/srvd_a2x/sap/whsefixedbinassignment/0001|0|WarehouseFixedBinAssignment||Warehouse Fixed Bin Assignment (A2X)\nCE_WHSEPHYSICALSTOCKPRODUCTS_0001|v4|/sap/opu/odata4/sap/api_whse_physstockprod/srvd_a2x/sap/whsephysicalstockproducts/0001|0|WarehousePhysicalStockProducts|WhsePhysStockProdSerialNumber|Warehouse Physical Stock by Product - Read, Update (A2X)\nCODING_0001|v4|/sap/opu/odata4/sap/api_coding/srvd_a2x/sap/coding/0001|0|MaintNotifCodingCode||Catalog Type Coding - Read\nCONDITIONCONTRACT_0001|v4|/sap/opu/odata4/sap/api_condition_contract/srvd_a2x/sap/conditioncontract/0001|0|BusVolSelectionCriteria|CndnContrCndnRecordValidity|Condition Contract\nCONTRACTACCOUNT_0001|v4|/sap/opu/odata4/sap/api_contractaccount/srvd_a2x/sap/contractaccount/0001|0|ContractAccount|ContractAccountPartner|Contract Account\nCTRSERVICE_0001|v4|/sap/opu/odata4/sap/br_ctr_service_o4/srvd_a2x/sap/ctrservice/0001|0|ajapurado|ajus_comp|CTR Complementary Table Data Maintenance\nC_BEHQUEUEDATA_CDS|v2|/sap/opu/odata/sap/C_BEHQUEUEDATA_CDS|0|C_Behqueuedata|I_BusinessObjectKeys|Business Events Queue - Read\nC_TRIALBALANCE_CDS|v2|/sap/opu/odata/sap/C_TRIALBALANCE_CDS|0|Ledger|CompanyCode|Trial Balance - Read\nDEFECT_0001|v4|/sap/opu/odata4/sap/api_defect/srvd_a2x/sap/defect/0001|0|Defect|DefectDetailedDescription|Defect\nDETECTIONMETHOD_0001|v4|/sap/opu/odata4/sap/api_detectionmethod/srvd_a2x/sap/detectionmethod/0001|0|MaintNotifDetCatGroup|MaintNotifDetCode|Detection Method - Read\nEHSAMOUNTEXTERNALSRCE_0001|v4|/sap/opu/odata4/sap/api_ehsamountexternalsrce/srvd_a2x/sap/ehsamountexternalsrce/0001|0|EHSAmountExternalSource||Environment, Health, and Safety External Source - Read, Collect Amounts\nELECTRONICDOCFILE_0001|v4|/sap/opu/odata4/sap/api_electronicdocfile/srvd_a2x/sap/electronicdocfile/0001|0|ElectronicDocFile||Document Compliance - Electronic Document File\nENTPROJECTPRIORITYCODE_0001|v4|/sap/opu/odata4/sap/api_entprojprioritycode/srvd_a2x/sap/entprojectprioritycode/0001|0|EntProjPriorityCode|EntProjPriorityCodeText|Enterprise Project - Read Project Priority\nENTPROJECTPROCESSINGSTATUS_0001|v4|/sap/opu/odata4/sap/api_entprojprocessingstat/srvd_a2x/sap/entprojectprocessingstatus/0001|0|ProcessingStatus|ProcessingStatusText|Enterprise Project - Read Project Processing Status\nENTPROJECTPROFILECODE_0001|v4|/sap/opu/odata4/sap/api_entprojectprofilecode/srvd_a2x/sap/entprojectprofilecode/0001|0|ProjectProfileCode|ProjectProfileCodeText|Enterprise Project - Read Project Profile\nENTPROJECTTYPE_0001|v4|/sap/opu/odata4/sap/api_enterpriseprojecttype/srvd_a2x/sap/entprojecttype/0001|0|EntProjectType|EntProjectTypeText|Enterprise Project - Read Internal Project Type\nEQUIPMENTSTRUCLIST_0001|v4|/sap/opu/odata4/sap/api_equipment_struclist/srvd_a2x/sap/equipmentstruclist/0001|0|EquipmentStructureList||Equipment Hierarchy – Read\nFIXEDASSETUSAGEOBJECT_0001|v4|/sap/opu/odata4/sap/api_fixedassetusageobject/srvd_a2x/sap/fixedassetusageobject/0001|0|FixedAssetUsageObject|FixedAssetUsageObjectPeriod|Fixed Asset: Usage Object for UoP Depreciation\nFLDLOGSSHIPMENTVOYAGE_0001|v4|/sap/opu/odata4/sap/api_fldlogsshipmentvoyage/srvd_a2x/sap/fldlogsshipmentvoyage/0001|0|FieldLogisticsShipmentVoyage|FieldLogisticsShipmentVoyStage|Field Logistics - Shipment Voyage\nFUNCNLLOCSTRUCLIST_0001|v4|/sap/opu/odata4/sap/api_funcnlloc_struclist/srvd_a2x/sap/funcnllocstruclist/0001|0|FuncnlLocEquipStrucList|FunctionalLocationStrucList|Functional Location Hierarchy – Read\nHANDLINGUNIT_0001|v4|/sap/opu/odata4/sap/api_handlingunit/srvd_a2x/sap/handlingunit/0001|0|HandlingUnit|HandlingUnitAlternativeID|Handling Unit\nINHOUSEREPAIR_0001|v4|/sap/opu/odata4/sap/api_inhouserepair/srvd_a2x/sap/inhouserepair/0001|0|InHouseRepair|InHouseRepairItem|In-House Repair\nLABELFIELDCATALOG_0001|v4|/sap/opu/odata4/sap/api_prodcmplnclblfldctlg/srvd_a2x/sap/labelfieldcatalog/0001|0|LabelFieldGroup|LabelFieldGroupText|Label Field Catalog ‒ Read\nMAINTENANCETASKLIST_0001|v4|/sap/opu/odata4/sap/api_maintenancetasklist/srvd_a2x/sap/maintenancetasklist/0001|0|MaintenanceTaskList|MaintenanceTaskListLongText|Maintenance Task List\nMANAGEUSERDEFINEDCRITERIA_0001|v4|/sap/opu/odata4/sap/api_suplrevalusrdfndcritra/srvd_a2x/sap/manageuserdefinedcriteria/0001|0|SuplrEvalUserDefinedCriterion||Manage User-Defined Criteria\nMEASUREMENTDOCUMENT_0001|v4|/sap/opu/odata4/sap/api_measurementdocument/srvd_a2x/sap/measurementdocument/0001|0|FailedMeasurementReading|MeasurementDocument|Measurement Document\nMEASURINGPOINT_0001|v4|/sap/opu/odata4/sap/api_measuringpoint/srvd_a2x/sap/measuringpoint/0001|0|MeasuringPoint|MeasuringPointCondition|Measuring Point\nMRPCHANGEREQUESTPRIORITY_0001|v4|/sap/opu/odata4/sap/api_mrpcr_priority_code/srvd_a2x/sap/mrpchangerequestpriority/0001|0|MRPChangeRequestPriority|MRPChangeRequestPriorityTxt|MRP Change Request Priority – Read\nMRPCHANGEREQUESTREASON_0001|v4|/sap/opu/odata4/sap/api_mrpcr_reason_code/srvd_a2x/sap/mrpchangerequestreason/0001|0|MRPChangeRequestReason|MRPChangeRequestReasonTxt|MRP Change Request Reason – Read\nMRPCHANGEREQUESTREJECTION_0001|v4|/sap/opu/odata4/sap/api_mrpcr_rejection_code/srvd_a2x/sap/mrpchangerequestrejection/0001|0|MRPChangeRequestRejection|MRPChangeRequestRejectionTxt|MRP Change Request Rejection – Read\nOBJECTPART_0001|v4|/sap/opu/odata4/sap/api_objectpart/srvd_a2x/sap/objectpart/0001|0|MaintNotifObjPrtCode||Catalog Type Object Parts – Read\nOVWDAMAGE_0001|v4|/sap/opu/odata4/sap/api_ovwdamage/srvd_a2x/sap/ovwdamage/0001|0|MaintNotifOvwDamageCode||Catalog Type Overview of Damage - Read\nPAYMENTREQUISITIONCN_0001|v4|/sap/opu/odata4/sap/api_cn_paymentrequisition/srvd_a2x/sap/paymentrequisitioncn/0001|0|PaymentStrategy|Requisition|Payment Requisition (Synchronous)\nPLANNEDORDER_0001|v4|/sap/opu/odata4/sap/api_plannedorder/srvd_a2x/sap/plannedorder/0001|0|PlannedOrderCapacity|PlannedOrderComponent|Planned Order\nPLANTEXCLUSION_0001|v4|/sap/opu/odata4/sap/api_plantsubstnexclsn/srvd_a2x/sap/plantexclusion/0001|1|A_PlantSubstnExclsn||Plant Substitution Exclusion\nPLANTSUBSTITUTIONCONTROL_0001|v4|/sap/opu/odata4/sap/api_plantsubstnctrl/srvd_a2x/sap/plantsubstitutioncontrol/0001|0|A_PlantSubstnCtrl|A_PlantSubstnCtrlGrp|Plant Substitution Control\nPLANTSUBSTITUTIONGROUP_0001|v4|/sap/opu/odata4/sap/api_plantsubstngrp/srvd_a2x/sap/plantsubstitutiongroup/0001|0|A_PlantSubstnGrp|A_PlantSubstnGrpText|Plant Substitution Group\nPLANTSUBSTITUTION_0001|v4|/sap/opu/odata4/sap/api_plantsubstn/srvd_a2x/sap/plantsubstitution/0001|1|A_PlantSubstn||Plant Substitution\nPMRPFLEXIBLECONSTRAINT_0001|v4|/sap/opu/odata4/sap/api_pmrpflexibleconstraint/srvd_a2x/sap/pmrpflexibleconstraint/0001|0|ConstraintProduct|Period|Maintain Flexible Constraints for predictive material and resource planning\nPRAFUNDSTRANSFER_0001|v4|/sap/opu/odata4/sap/api_prafundstransfer/srvd_a2x/sap/prafundstransfer/0001|0|FundsDetail|FundsHeader|PRA Funds Transfer\nPRAMAINTDOI_0001|v4|/sap/opu/odata4/sap/api_pramaintdoi_o4/srvd_a2x/sap/pramaintdoi/0001|0|Bearer|BearerGroup|PRA Division of Interest\nPRAMAINTUNITVNTRCTRL_0001|v4|/sap/opu/odata4/sap/api_pramaintunitvntctrl_o4/srvd_a2x/sap/pramaintunitvntrctrl/0001|0|Tract|UseControl|PRA Unit Tract Participation\nPRAMAINTVENTURE_0001|v4|/sap/opu/odata4/sap/api_pramaintventure_o4/srvd_a2x/sap/pramaintventure/0001|0|UnitVenture|Venture|PRA Joint Venture\nPRODHIERNODES_0001|v4|/sap/opu/odata4/sap/api_prod_hier_nodes_srv/srvd_a2x/sap/prodhiernodes/0001|0|ProdUniversalHierarchy|ProdUniversalHierarchyText|Product Hierarchy Nodes - Read, Create\nPRODHIERPRODS_0001|v4|/sap/opu/odata4/sap/api_prod_hier_prods_srv/srvd_a2x/sap/prodhierprods/0001|0|ProdUnivHierNormalNode|ProdUnivHierProdByHierNode|Products to Product Hierarchies Assignment - Read, Create\nPRODTIMEDPDNTSTCK_0001|v4|/sap/opu/odata4/sap/api_prod_timedpdntstck_srv/srvd_a2x/sap/prodtimedpdntstck/0001|0|A_ProdTimeDepdntStockLvl||Time Dependent Stock Levels\nPRODUCTEXCLUSION_0001|v4|/sap/opu/odata4/sap/api_prodsubstnexclsn/srvd_a2x/sap/productexclusion/0001|1|A_ProdSubstnExclsn||Product Exclusion\nPRODUCTGROUP_0001|v4|/sap/opu/odata4/sap/api_productgroup_2/srvd_a2x/sap/productgroup/0001|0|ProductGroup|ProductGroupText|Product Group Data - Read\nPRODUCTSUBSTITUTIONCTRL_0001|v4|/sap/opu/odata4/sap/api_prodsubstnctrl/srvd_a2x/sap/productsubstitutionctrl/0001|1|A_ProdSubstnCtrl|A_ProdSubstnCtrlGrp|Product Substitution Control\nPRODUCTSUBSTITUTIONGROUP_0001|v4|/sap/opu/odata4/sap/api_prodsubstngrp/srvd_a2x/sap/productsubstitutiongroup/0001|0|A_ProdSubstnGrp|A_ProdSubstnGrpText|Product Substitution Group\nPRODUCTSUBSTITUTION_0001|v4|/sap/opu/odata4/sap/api_productsubstitution/srvd_a2x/sap/productsubstitution/0001|1|A_ProdSubstn||Product Substitution\nPRODUCTTYPE_0001|v4|/sap/opu/odata4/sap/api_producttype/srvd_a2x/sap/producttype/0001|0|ProductType|ProductTypeText|Product Type - Read\nPRODUCT_0001|v4|/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0001|1|ProdSalesDeliverySalesTax|Product|Product\nQUALITYTASK_0001|v4|/sap/opu/odata4/sap/api_qualitytask/srvd_a2x/sap/qualitytask/0001|0|QualityTask|QualityTaskLongText|Quality Task\nRECONTRACTTYPE_0001|v4|/sap/opu/odata4/sap/api_re_contracttype/srvd_a2x/sap/recontracttype/0001|0|REContractType|REContractTypeText|Real Estate Contract Type\nREINTEGRATIONOBJECT_0001|v4|/sap/opu/odata4/sap/api_re_integrationobject/srvd_a2x/sap/reintegrationobject/0001|0|REIntegObjectAddress|REIntegObjectContractAssgmt|Real Estate Integration Object\nRESPACEGRPENABLEUSETYPE_0001|v4|/sap/opu/odata4/sap/api_re_spacegrpusetype/srvd_a2x/sap/respacegrpenableusetype/0001|0|RESpaceGrpEnableUseType|RESpaceGrpEnableUseTypeText|Real Estate Usage Enablement Type\nSALESAREA_0001|v4|/sap/opu/odata4/sap/api_salesarea/srvd_a2x/sap/salesarea/0001|0|SalesArea||Sales Area - Read (A2X)\nSALESPRICE_0001|v4|/sap/opu/odata4/sap/api_salesprice/srvd_a2x/sap/salesprice/0001|1|SalesPrice||Sales Price - Retrieve (A2X)\nSERVICECANCELLATIONPARTY_0001|v4|/sap/opu/odata4/sap/api_srvccancellationparty/srvd_a2x/sap/servicecancellationparty/0001|0|ServiceCancellationParty|ServiceCancellationPartyText|Service Cancellation Party - Read\nSERVICECANCELLATIONREASON_0001|v4|/sap/opu/odata4/sap/api_srvccancellationreason/srvd_a2x/sap/servicecancellationreason/0001|0|ServiceCancellationReason|ServiceCancellationReasonText|Service Cancellation Reason - Read\nSERVICEDOCUMENTPRIORITY_0001|v4|/sap/opu/odata4/sap/api_srvcdocumentpriority/srvd_a2x/sap/servicedocumentpriority/0001|0|ServiceDocumentPriority|ServiceDocumentPriorityText|Service Transaction Priority - Read\nSERVICEDOCUMENTTYPE_0001|v4|/sap/opu/odata4/sap/api_srvcdocumenttype/srvd_a2x/sap/servicedocumenttype/0001|0|ServiceDocumentType|ServiceDocumentTypeText|Service Transaction Type - Read\nSETTLMTDOCTYPE_0001|v4|/sap/opu/odata4/sap/api_settlmt_doc_type/srvd_a2x/sap/settlmtdoctype/0001|0|SetlMgmtHdrTxtObjTypeAssgmt|SetlMgmtItmTxtObjTypeAssgmt|Settlement Document Type - Read\nSETTLMTDOC_0001|v4|/sap/opu/odata4/sap/api_settlmt_doc/srvd_a2x/sap/settlmtdoc/0001|0|CustItmPricingElement|CustPricingElement|Settlement Document\nSETTLMTPROCESSTYPE_0001|v4|/sap/opu/odata4/sap/api_settlmt_proc_type/srvd_a2x/sap/settlmtprocesstype/0001|0|SettlmtApplStsGrpStsAssgmt|SettlmtDocProcTypeAssgmt|Settlement Process Type - Read\nSHIPMENTCONTAINERUNIT_0001|v4|/sap/opu/odata4/sap/api_shipmentcontainerpackg/srvd_a2x/sap/shipmentcontainerunit/0001|0|ShipmentContainer|ShipmentContainerItem|Pack Containers\nSLSPRCGACCESSSEQUENCE_0001|v4|/sap/opu/odata4/sap/api_slsprcgaccesssequence/srvd_a2x/sap/slsprcgaccesssequence/0001|0|SalesPricingAccess|SalesPricingAccessField|Access Sequence for Pricing in Sales – Read\nSLSPRCGCNDNEXCLUSION_0001|v4|/sap/opu/odata4/sap/api_slsprcgcndnexclusion/srvd_a2x/sap/slsprcgcndnexclusion/0001|0|SlsCndnExclsnForPrcgProced|SlsPrcgCndnExclsnGroupText|Condition Exclusion for Pricing in Sales – Read\nSLSPRCGCONDITIONFIELD_0001|v4|/sap/opu/odata4/sap/api_slsprcgconditionfield/srvd_a2x/sap/slsprcgconditionfield/0001|0|SalesPricingConditionField|SlsPricingConditionFieldText|Field Catalog for Pricing in Sales – Read\nSLSPRCGCONDITIONTABLE_0001|v4|/sap/opu/odata4/sap/api_slsprcgconditiontable/srvd_a2x/sap/slsprcgconditiontable/0001|0|SalesPricingConditionTable|SlsPrcgConditionTableField|Condition Table for Pricing in Sales – Read\nSRVCDOCUMENTITEMCATEGORY_0001|v4|/sap/opu/odata4/sap/api_srvcdocitemcategory/srvd_a2x/sap/srvcdocumentitemcategory/0001|0|SrvcDocumentItemCategory|SrvcDocumentItemCategoryText|Service Transaction Item Category - Read\nSSPOPENITMGOODSRECEIPT_0001|v4|/sap/opu/odata4/sap/api_sspopenitmgoodsreceipt/srvd_a2x/sap/sspopenitmgoodsreceipt/0001|0|A_SSPOpenItemGoodsReceipt||Goods Receipt Confirmations\nSTORAGELOCATIONEXCLUSION_0001|v4|/sap/opu/odata4/sap/api_storlocsubstnexclsn/srvd_a2x/sap/storagelocationexclusion/0001|1|A_StorageLocationSubstnExclsn||Storage Location Substitution Exclusion\nSTORAGELOCATIONSUBSTNCTRL_0001|v4|/sap/opu/odata4/sap/api_storlocsubstnctrl/srvd_a2x/sap/storagelocationsubstnctrl/0001|0|A_StorageLocationSubstnCtrl|A_StorageLocationSubstnCtrlGrp|Storage Location Substitution Control\nSTORAGELOCATIONSUBSTNGROUP_0001|v4|/sap/opu/odata4/sap/api_storlocsubstngrp/srvd_a2x/sap/storagelocationsubstngroup/0001|0|A_StorageLocationSubstnGrp|A_StorageLocationSubstnGrpText|Storage Location Substitution Group\nSTORLOCSUBSTITUTION_0001|v4|/sap/opu/odata4/sap/api_storlocsubstn/srvd_a2x/sap/storlocsubstitution/0001|1|A_StorageLocationSubstn||Storage Location Substitution\nSUPPLIERITEM_0001|v4|/sap/opu/odata4/sap/api_supplieritem/srvd_a2x/sap/supplieritem/0001|0|SupplierItem|SupplierItemAttachment|Supplier Item - Create, Read, Update\nTASKCODE_0001|v4|/sap/opu/odata4/sap/api_taskcode/srvd_a2x/sap/taskcode/0001|0|TaskCode|TaskCodeText|Task Code - Read\nUSAGEDECISIONSELDCODESET_0001|v4|/sap/opu/odata4/sap/api_usagedcsnseldcodeset/srvd_a2x/sap/usagedecisionseldcodeset/0001|0|UsgeDcsnSeldCodeGroupText|UsgeDcsnSeldCodeSetText|Selected Set of Codes for Usage Decision - Read\nVARCONFIGNOBJECTDEPENDENCY_0001|v4|/sap/opu/odata4/sap/api_varcnfobjectdependency/srvd_a2x/sap/varconfignobjectdependency/0001|0|VarCnfHistlObjDependencyText|VarCnfHistlObjectDependency|Object Dependency\nVARCONFIGNTABLECONTENT_0001|v4|/sap/opu/odata4/sap/api_varconfigntablecontent/srvd_a2x/sap/varconfigntablecontent/0001|0|VarConfigurationTableContent|VariantConfigurationTableLine|Variant Table Content\nVARCONFIGNTABLE_0001|v4|/sap/opu/odata4/sap/api_varconfigntable/srvd_a2x/sap/varconfigntable/0001|0|VarCnfTblValAssgmtAlternative|VarCnfTblValAssgmtAltvItem|Variant Table\nVARCONFIGURATIONPROFILE_0001|v4|/sap/opu/odata4/sap/api_varcnfprofile/srvd_a2x/sap/varconfigurationprofile/0001|0|VarCnfHistoricalProfile|VarCnfProfileCharcGroupAssgmt|Variant Configuration Profile\nWAREHOUSEAVAILABLESTOCK_0001|v4|/sap/opu/odata4/sap/api_whse_availablestock/srvd_a2x/sap/warehouseavailablestock/0001|0|WarehouseAvailableStock||Warehouse Available Stock - Read (A2X)\nWAREHOUSEINBOUNDDELIVERY_0001|v4|/sap/opu/odata4/sap/api_whse_inb_delivery_2/srvd_a2x/sap/warehouseinbounddelivery/0001|0|WhseInbDelivItemSerialNumber|WhseInboundDeliveryHead|Warehouse Inbound Delivery - Read, Update (A2X)\nWAREHOUSEORDER_0001|v4|/sap/opu/odata4/sap/api_warehouse_order_task_2/srvd_a2x/sap/warehouseorder/0001|0|WarehouseOrder|WarehouseOrderPickHndlgUnit|Warehouse Order and Task (A2X)\nWAREHOUSEOUTBDELIVERYORDER_0001|v4|/sap/opu/odata4/sap/api_warehouse_odo_2/srvd_a2x/sap/warehouseoutbdeliveryorder/0001|0|WhseDeliveryDocumentAddress|WhseOutbDelivOrderItemSerialNo|Warehouse Outbound Delivery Order - Read, Update (A2X)\nWAREHOUSERESOURCE_0001|v4|/sap/opu/odata4/sap/api_warehouse_resource_2/srvd_a2x/sap/warehouseresource/0001|0|WarehouseResource|WhseResourceGroupQueueSqnc|Warehouse Resource (A2X)\nWAREHOUSESTORAGEBIN_0001|v4|/sap/opu/odata4/sap/api_whse_storage_bin_2/srvd_a2x/sap/warehousestoragebin/0001|0|WarehouseStorageBin||Warehouse Storage Bin (A2X)\nWAREHOUSE_0001|v4|/sap/opu/odata4/sap/api_warehouse_2/srvd_a2x/sap/warehouse/0001|0|Warehouse|WarehouseStorageType|Warehouse - Read (A2X)\nWARRANTYCLAIM_0001|v4|/sap/opu/odata4/sap/api_warrantyclaim/srvd_a2x/sap/warrantyclaim/0001|0|WarrantyClaim|WarrantyClaimItem|Warranty Claim\nWHSEPHYSICALINVENTORYDOC_0001|v4|/sap/opu/odata4/sap/api_whse_physinvtryitem_2/srvd_a2x/sap/whsephysicalinventorydoc/0001|0|WhsePhysicalInventoryCountItem|WhsePhysicalInventoryItem|Warehouse Physical Inventory (A2X)\nWORKCENTERPOOLEDCAPACITY_0001|v4|/sap/opu/odata4/sap/api_wrkctrpooledcapacity/srvd_a2x/sap/workcenterpooledcapacity/0001|0|WorkCenterCapacity|WorkCenterCapacityInterval|Pooled Capacity\nWORKCENTER_0001|v4|/sap/opu/odata4/sap/api_work_center/srvd_a2x/sap/workcenter/0001|0|WorkCenterCapacity|WorkCenterCapacityDescription|Work Center\n_CPD_SC_EXTERNAL_SERVICES_SRV|v2|/sap/opu/odata/CPD/SC_EXTERNAL_SERVICES_SRV|0|ProjectSet|WorkpackageSet|Commercial Project - Read\n_CPD_SC_PROJ_ENGMT_CREATE_UPD_SRV|v2|/sap/opu/odata/CPD/SC_PROJ_ENGMT_CREATE_UPD_SRV|0|A_CustProjSlsOrd|A_CustProjSlsOrdItem|Commercial Project - Create, Update\nsap-s4-API_EQUIPMENT-v1|v2|/sap/opu/odata/sap/API_EQUIPMENT|0|Equipment|EquipClassCharacteristicValue|Equipment\nsap-s4-API_FUNCTIONALLOCATION-v1|v2|/sap/opu/odata/sap/API_FUNCTIONALLOCATION|0|Value|FunctionalLocationClass|Functional Location\nsap-s4-CAMASTERDATAID_0001-v1|v4|/sap/opu/odata4/sap/api_camasterdataid/srvd_a2x/sap/camasterdataid/0001|0|CAMasterDataID|CAMasterDataText|Convergent Invoicing Master Data ID\nsap-s4-CE_API_ACCOUNTING_DOCUMENT_0001-v1|v4|/sap/opu/odata4/dco/api_accounting_document/srvd_a2x/dco/api_accounting_document/0001|0|DCoAccountingDocument||Receivable Item - Read\nsap-s4-CE_API_BUS_SITN_MSTRDATA_SRV_V4_0001-v1|v4|/sap/opu/odata4/sap/api_bus_sitn_mstrdata_srv_v4/srvd_a2x/sap/situationmasterdata/0001|0|SituationObject|SituationObjectStructure|Business Situation Type - Read\nsap-s4-CE_API_CDR_FILE_PREPARE_0001-v1|v4|/sap/opu/odata4/sap/api_cdr_file_prepare/srvd_a2x/sap/api_cdr_file_prepare/0001|0|Package||Customer Data Return - File Prepare\nsap-s4-CE_API_CNSLDTNRLBSDGRPJRNLITM_0001-v1|v2|/sap/opu/odata/sap/API_CNSLDTNRLBSDGRPJRNLITM|0|CnsldtnRuleBsdGrpJrnlItemResults|CnsldtnRuleBsdGrpJrnlItem|Consolidation Group Journal Entry for Reporting Rule\nsap-s4-CE_API_DISPUTE_MANAGE_0001-v1|v4|/sap/opu/odata4/dco/api_dispute_manage/srvd_a2x/dco/api_dispute_manage/0001|0|DCoDisputeAccountingDocument|DCoDisputeResubmission|Dispute\nsap-s4-CE_API_MATERIALSERIALNUMBER_0001-v1|v4|/sap/opu/odata4/sap/api_materialserialnumber/srvd_a2x/sap/materialserialnumber/0001|0|MaterialSerialNumber|MaterialSerialNumberPartner|Material Serial Number\nsap-s4-CE_API_PRODUCTION_ROUTING_0003-v3|v2|/sap/opu/odata/sap/API_PRODUCTION_ROUTING|0|OpInspCharcsAssgmt|ProductionRoutingOpCtrlPrflTxt|Production Routing\nsap-s4-CE_API_PROMISE_TO_PAY_MANAGE_0001-v1|v4|/sap/opu/odata4/dco/api_promise_to_pay_manage/srvd_a2x/dco/api_promise_to_pay_manage/0001|0|DCoPrms2PInstallmentPlanItem|DCoPrmsToPayAccountingDocument|Promise to Pay\nsap-s4-CE_ASSETREVALUATIONINDEX_0001-v1|v4|/sap/opu/odata4/sap/api_assetrevaluationindex/srvd_a2x/sap/assetrevaluationindex/0001|0|AssetRevaluationIndex|AssetRevaluationIndexItem|Fixed Asset - Revaluation Index\nsap-s4-CE_BILLINGDOCUMENTREQUEST_0001-v1|v4|/sap/opu/odata4/sap/api_billingdocumentrequest/srvd_a2x/sap/billingdocumentrequest/0001|0|BillingDocRequestItemPartner|BillingDocumentRequest|Billing Document Request\nsap-s4-CE_BILLINGDOCUMENT_0001-v1|v4|/sap/opu/odata4/sap/api_billingdocument/srvd_a2x/sap/billingdocument/0001|0|BillingDocument|BillingDocumentItem|Billing Document\nsap-s4-CE_BUSINESSSOLUTIONORDER_0001-v1|v4|/sap/opu/odata4/sap/api_businesssolutionorder/srvd_a2x/sap/businesssolutionorder/0001|0|BSOrdItmBillgReqItmPrcElm|BSOrdItmRateElement|Business Solution Order (A2X)\nsap-s4-CE_BUSSOLNORDERSIMULATION_0001-v1|v4|/sap/opu/odata4/sap/api_bussolnordsimulation/srvd_a2x/sap/bussolnordersimulation/0001|0|BusinessSolutionOrder|BusinessSolutionOrderItem|Business Solution Order - Simulate (A2X)\nsap-s4-CE_CADISPUTECASE_0002-v2|v4|/sap/opu/odata4/sap/api_cadisputecase/srvd_a2x/sap/cadisputecase/0002|0|DisputeCase|DisputeCaseObject|Contract Accounting Dispute Case\nsap-s4-CE_CHANGERECORD_0001-v1|v4|/sap/opu/odata4/sap/api_changerecord_2/srvd_a2x/sap/changerecord/0001|0|ChangeRecord|ReferenceBOM|Change Record\nsap-s4-CE_CHGRECDNEXTUSERSTATUSACTN_0001-v1|v4|/sap/opu/odata4/sap/api_chgrecdnextuserstatusactn/srvd_a2x/sap/chgrecdnextuserstatusactn/0001|0|ChangeRecordNextStatus||Change Record Next User Status Action\nsap-s4-CE_CNSLDTNBILLINGDOCTYPEHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnblgdoctypehier/srvd_a2x/sap/cnsldtnbillingdoctypehierarchy/0001|0|CnsldtnBillgDocTypeHierNdeText|CnsldtnBillingDocTypeHierarchy|Consolidation Billing Document Type Hierarchy\nsap-s4-CE_CNSLDTNCOSTCENTERHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtncostcenterhier/srvd_a2x/sap/cnsldtncostcenterhierarchy/0001|0|CnsldtnCostCenterHierarchy|CnsldtnCostCenterHierarchyNode|Consolidation Cost Center Hierarchy\nsap-s4-CE_CNSLDTNDISTRCHANNELHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtndistrchnlhier/srvd_a2x/sap/cnsldtndistrchannelhierarchy/0001|0|CnsldtnDistrChannelHierarchy|CnsldtnDistrChannelHierNode|Consolidation Distribution Channel Hierarchy\nsap-s4-CE_CNSLDTNDIVISIONHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtndivisionhier/srvd_a2x/sap/cnsldtndivisionhierarchy/0001|0|CnsldtnDivisionHierarchy|CnsldtnDivisionHierarchyNode|Consolidation Division Hierarchy\nsap-s4-CE_CNSLDTNFINANCIALDATASOURCE_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfindatasource/srvd_a2x/sap/cnsldtnfinancialdatasource/0001|0|CnsldtnFinancialDataSource|CnsldtnFinancialDataSourceText|Consolidation Financial Data Source\nsap-s4-CE_CNSLDTNFINANCIALMANAGEMENTAREA_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfinmgmtarea/srvd_a2x/sap/cnsldtnfinancialmanagementarea/0001|0|CnsldtnFinancialManagementArea|CnsldtnFinManagementAreaText|Consolidation Financial Management Area\nsap-s4-CE_CNSLDTNFINANCIALSERVICESBRANCH_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfinsrvcsbranch/srvd_a2x/sap/cnsldtnfinancialservicesbranch/0001|0|CnsldtnFinancialServicesBranch|CnsldtnFinServicesBranchText|Consolidation Financial Services Branch\nsap-s4-CE_CNSLDTNFINSERVICESPRODUCTGROUP_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfinsrvcsprodgrp/srvd_a2x/sap/cnsldtnfinservicesproductgroup/0001|0|CnsldtnFinServicesProductGroup|CnsldtnFinSrvcsProdGroupText|Consolidation Financial Services Product Group\nsap-s4-CE_CNSLDTNFINSRVCSPRODGROUPHIER_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfinsprodgrphier/srvd_a2x/sap/cnsldtnfinsrvcsprodgrouphier/0001|0|CnsldtnFinSProdGrpHierNodeText|CnsldtnFinSrvcsProdGroupHier|Consolidation Financial Services Product Group Hierarchy\nsap-s4-CE_CNSLDTNFUNCNLAREAHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfuncnlareahier/srvd_a2x/sap/cnsldtnfuncnlareahierarchy/0001|0|CnsldtnFuncnlAreaHierarchy|CnsldtnFuncnlAreaHierarchyNode|Consolidation Functional Area Hierarchy\nsap-s4-CE_CNSLDTNFUNDHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfundhierarchy/srvd_a2x/sap/cnsldtnfundhierarchy/0001|0|CnsldtnFundHierarchy|CnsldtnFundHierarchyNode|Consolidation Fund Hierarchy\nsap-s4-CE_CNSLDTNFUND_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnfund/srvd_a2x/sap/cnsldtnfund/0001|0|CnsldtnFund|CnsldtnFundText|Consolidation Fund\nsap-s4-CE_CNSLDTNGRANTHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtngranthierarchy/srvd_a2x/sap/cnsldtngranthierarchy/0001|0|CnsldtnGrantHierarchy|CnsldtnGrantHierarchyNode|Consolidation Grant Hierarchy\nsap-s4-CE_CNSLDTNGRANT_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtngrant/srvd_a2x/sap/cnsldtngrant/0001|0|CnsldtnGrant|CnsldtnGrantText|Consolidation Grant\nsap-s4-CE_CNSLDTNINDUSTRYHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnindustryhier/srvd_a2x/sap/cnsldtnindustryhierarchy/0001|0|CnsldtnIndustryHierarchy|CnsldtnIndustryHierarchyNode|Consolidation Industry Hierarchy\nsap-s4-CE_CNSLDTNMATERIALGROUP_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnmaterialgroup/srvd_a2x/sap/cnsldtnmaterialgroup/0001|0|CnsldtnMaterialGroup|CnsldtnMaterialGroupText|Consolidation Material Group\nsap-s4-CE_CNSLDTNMATERIAL_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnmaterial/srvd_a2x/sap/cnsldtnmaterial/0001|0|CnsldtnMaterial|CnsldtnMaterialText|Consolidation Material\nsap-s4-CE_CNSLDTNPLANTHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnplanthierarchy/srvd_a2x/sap/cnsldtnplanthierarchy/0001|0|CnsldtnPlantHierarchy|CnsldtnPlantHierarchyNode|Consolidation Plant Hierarchy\nsap-s4-CE_CNSLDTNPRODUCTGROUPHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnprodgrouphier/srvd_a2x/sap/cnsldtnproductgrouphierarchy/0001|0|CnsldtnProdGroupHierNodeText|CnsldtnProductGroupHierarchy|Consolidation Product Group Hierarchy\nsap-s4-CE_CNSLDTNPROFITCENTERHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnprftctrhier/srvd_a2x/sap/cnsldtnprofitcenterhierarchy/0001|0|CnsldtnPrftCtrHierNodeText|CnsldtnProfitCenterHierarchy|Consolidation Profit Center Hierarchy\nsap-s4-CE_CNSLDTNSALESDISTRICTHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnsalesdstrcthier/srvd_a2x/sap/cnsldtnsalesdistricthierarchy/0001|0|CnsldtnSalesDistrictHierarchy|CnsldtnSalesDistrictHierNode|Consolidation Sales District Hierarchy\nsap-s4-CE_CNSLDTNSEGMENTHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnsegmenthier/srvd_a2x/sap/cnsldtnsegmenthierarchy/0001|0|CnsldtnSegmentHierarchy|CnsldtnSegmentHierarchyNode|Consolidation Segment Hierarchy\nsap-s4-CE_COMPLIANCEREQUIREMENTVERSION_0001-v1|v4|/sap/opu/odata4/sap/api_compliancereqvers/srvd_a2x/sap/compliancerequirementversion/0001|0|ComplianceRequirementVersion||Compliance Requirement Version - Read\nsap-s4-CE_CONSOLIDATIONANSWER_0001-v1|v4|/sap/opu/odata4/sap/api_consolidationanswer/srvd_a2x/sap/consolidationanswer/0001|0|ConsolidationAnswer|ConsolidationAnswerText|Consolidation Answer\nsap-s4-CE_CONSOLIDATIONGLOBALPARAMETER_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnglobalparameter/srvd_a2x/sap/consolidationglobalparameter/0001|0|ConsolidationGlobalParameter||Consolidation Global Parameter\nsap-s4-CE_CONSOLIDATIONGROUP_0001-v1|v4|/sap/opu/odata4/sap/api_consolidationgroup/srvd_a2x/sap/consolidationgroup/0001|0|ConsolidationGroup|ConsolidationGroupText|Consolidation Group\nsap-s4-CE_CONSOLIDATIONQUESTIONTYPE_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnquestiontype/srvd_a2x/sap/consolidationquestiontype/0001|0|ConsolidationQuestionType|ConsolidationQuestionTypeText|Consolidation Question Type\nsap-s4-CE_CONSOLIDATIONQUESTION_0001-v1|v4|/sap/opu/odata4/sap/api_consolidationquestion/srvd_a2x/sap/consolidationquestion/0001|0|CnsldtnQstnAnswerAssignment|ConsolidationQuestion|Consolidation Question\nsap-s4-CE_CONSOLIDATIONREPORTEDANSWER_0001-v1|v4|/sap/opu/odata4/sap/api_cnsldtnreportedanswer/srvd_a2x/sap/consolidationreportedanswer/0001|0|ConsolidationReportedAnswer||Consolidation Reported Answer\nsap-s4-CE_CONSOLIDATIONSELECTION_0001-v1|v4|/sap/opu/odata4/sap/api_consolidationselection/srvd_a2x/sap/consolidationselection/0001|0|CnsldtnSelectionCondition|ConsolidationSelection|Consolidation Selection\nsap-s4-CE_CONSOLIDATIONTASKLOG_0001-v1|v4|/sap/opu/odata4/sap/api_consolidationtasklog/srvd_a2x/sap/consolidationtasklog/0001|0|CnsldtnTaskLogItemDataEntry|CnsldtnTskLgItemLineValidation|Consolidation Task Log - Read\nsap-s4-CE_COSTANDREVENUEREASSIGNMENT_0001-v1|v4|/sap/opu/odata4/sap/api_costandrevenuereassignment/srvd_a2x/sap/costandrevenuereassignment/0001|0|CostAndRevenueReassignment|CostAndRevenueReassignmentItem|Cost and Revenue Reassignment – Read, Create\nsap-s4-CE_COSTCENTERHIERARCHY_0001-v1|v4|/sap/opu/odata4/sap/api_costcenter_hierarchy/srvd_a2x/sap/costcenterhierarchy/0001|0|CostCenterHierarchy|CostCenterHierarchyNode|Cost Center Hierarchy\nsap-s4-CE_CREDITMEMOREQUEST_0001-v1|v4|/sap/opu/odata4/sap/api_creditmemorequest/srvd_a2x/sap/creditmemorequest/0001|0|CreditMemoReqItemPartner|CreditMemoReqItmPricingElement|Credit Memo Request (A2X)\nsap-s4-CE_CURRENCYEXCHANGERATE_0001-v1|v4|/sap/opu/odata4/sap/api_currencyexchangerate/srvd_a2x/sap/currencyexchangerate/0001|0|CurrencyExchangeRate||Currency Exchange Rate\nsap-s4-CE_CUSTOMERRETURNSIMULATION_0001-v1|v4|/sap/opu/odata4/sap/api_customerreturnsimulation/srvd_a2x/sap/customerreturnsimulation/0001|0|CustomerReturn|CustomerReturnItem|Customer Return - Simulate (A2X)\nsap-s4-CE_CUSTOMERRETURN_0001-v1|v4|/sap/opu/odata4/sap/api_customerreturn/srvd_a2x/sap/customerreturn/0001|0|CustomerReturn|CustomerReturnItem|Customer Return (A2X)\nsap-s4-CE_DANGEROUSGDSCLASSFCTN_0001-v1|v4|/sap/opu/odata4/sap/api_dangerousgdsclassfctn/srvd_a2x/sap/dangerousgdsclassfctn/0001|0|DangerousGoodsClassification|DngrsGdsClassfctnHandlingLabel|Dangerous Goods Classification\nsap-s4-CE_DEBITMEMOREQUEST_0001-v1|v4|/sap/opu/odata4/sap/api_debitmemorequest/srvd_a2x/sap/debitmemorequest/0001|0|DebitMemoReqItemPricingElement|DebitMemoReqPricingElement|Debit Memo Request (A2X)\nsap-s4-CE_DIRECTDEBITMANDATE_0001-v1|v4|/sap/opu/odata4/sap/api_directdebitmandate/srvd_a2x/sap/directdebitmandate/0001|0|DirectDebitMandate||Contract Accounting Direct Debit Mandates\nsap-s4-CE_DNGRSGDSBSCCLASSIFICATION_0001-v1|v4|/sap/opu/odata4/sap/api_dngrsgdsbasicclassfctn/srvd_a2x/sap/dngrsgdsbscclassification/0001|0|DngrsGdsBscClassfctnSgrgtnCode|DngrsGdsBscClassfctnSgrgtnGrp|Dangerous Goods Basic Classification\nsap-s4-CE_EBPPPAYMENTREQUEST_0001-v1|v4|/sap/opu/odata4/sap/api_ebpppaymentrequest/srvd_a2x/sap/ebpppaymentrequest/0001|0|EBPPARItem|EBPPARItemPaytCardData|EBPP Payment Request\nsap-s4-CE_ECOLOGICALPROPERTY_0001-v1|v4|/sap/opu/odata4/sap/api_ecologicalproperty/srvd_a2x/sap/ecologicalproperty/0001|0|EcologicalProperty|EcologicalPropertyOxygenDemand|Ecological Information\nsap-s4-CE_ENGAGEMENTPROJECTFORECAST_0001-v1|v4|/sap/opu/odata4/sap/api_engagementprojectforecast/srvd_a2x/sap/engagementprojectforecast/0001|0|ProjectForecast|ProjectResourceForecast|Commercial Project – Forecast\nsap-s4-CE_ENTPROJBLOCKFUNCTIONCODE_0001-v1|v4|/sap/opu/odata4/sap/api_entprojblkfunctioncode/srvd_a2x/sap/entprojblockfunctioncode/0001|0|EnterpriseProjectBlockFunction|EntProjectBlockFunctionText|Enterprise Project - Read Blockable Function\nsap-s4-CE_EXTERNALTAXHEADER_0001-v1|v4|/sap/opu/odata4/sap/api_external_tax/srvd_a2x/sap/externaltaxheader/0001|0|ExternalTax|ExternalTaxItem|Manage External Tax Items\nsap-s4-CE_FINTRANSINTRSTRATEINSTR_0002-v2|v4|/sap/opu/odata4/sap/api_fintransintrstrateinstr/srvd_a2x/sap/fintransintrstrateinstr/0002|1|AdditionalAttribute|AdditionalFlow|Financial Transaction Interest Rate Instrument\nsap-s4-CE_FINTRANSINTRSTRATEINSTR_0003-v3|v4|/sap/opu/odata4/sap/api_fintransintrstrateinstr/srvd_a2x/sap/fintransintrstrateinstr/0003|0|AdditionalAttribute|AdditionalFlow|Financial Transaction Interest Rate Instrument\nsap-s4-CE_FIXEDASSETREVALUATION_0001-v1|v4|/sap/opu/odata4/sap/api_fixedassetrevaluation/srvd_a2x/sap/fixedassetrevaluation/0001|0|FixedAssetRevalItemAmount|FixedAssetRevaluation|Fixed Asset - Post Asset Revaluation\nsap-s4-CE_FIXEDASSET_0001-v1|v4|/sap/opu/odata4/sap/api_fixedasset/srvd_a2x/sap/fixedasset/0001|0|FixedAsset|FixedAssetAssignment|Fixed Asset - Master Data\nsap-s4-CE_FOREIGNEXCHANGEEXPOSURE_0001-v1|v4|/sap/opu/odata4/sap/api_fxem_fxexposure/srvd_a2x/sap/foreignexchangeexposure/0001|0|ForeignExchangeExposure||Foreign Exchange Exposure\nsap-s4-CE_GHSCLASSFCTNASSESSMENT_0001-v1|v4|/sap/opu/odata4/sap/api_ghsclassfctnassessment/srvd_a2x/sap/ghsclassificationassessment/0001|0|GHSClassificationAssessment|GHSClfnAssmtAddlInfo|GHS Classification Assessment\nsap-s4-CE_GHSLABELINGASSESSMENT_0001-v1|v4|/sap/opu/odata4/sap/api_ghslabelingassessment/srvd_a2x/sap/ghslabelingassessment/0001|0|GHSLabelAssessment|GHSLabelAssessmentDisposal|GHS Labeling Assessment\nsap-s4-CE_HANDLINGANDSTORAGEASSESSMENT_0001-v1|v4|/sap/opu/odata4/sap/api_handlingandstorage/srvd_a2x/sap/handlingandstorageassessment/0001|0|FireMeasure|FireMeasureRating|Handling And Storage Assessment\nsap-s4-CE_HR_EMPLOYEEEXPENSE_0001-v1|v4|/sap/opu/odata4/sap/api_hr_employeeexpense/srvd_a2x/sap/hr_employeeexpense/0001|0|HR_EmployeeExpense||Croatia Employee Expense - Read\nsap-s4-CE_JITINBOUNDCALL_0001-v1|v4|/sap/opu/odata4/sap/api_jitinboundcall/srvd_a2x/sap/jitinboundcall/0001|0|JITInbCallActionLog|JITInbCallCompDocReference|Just-In-Time Inbound Call\nsap-s4-CE_JOINTVENTUREPARTNER_0001-v1|v4|/sap/opu/odata4/sap/api_jointventurepartner/srvd_a2x/sap/jointventurepartner/0001|0|JVABusinessPartner||Joint Venture Business Partner\nsap-s4-CE_LEGAL_DOCUMENT_0001-v1|v4|/sap/opu/odata4/sap/api_legal_document_2/srvd_a2x/sap/legaldocument/0001|0|LegalDocument|LglCntntMDocAgreement|Legal Document\nsap-s4-CE_LEGAL_TRANSACTION_0001-v1|v4|/sap/opu/odata4/sap/api_legal_transaction_2/srvd_a2x/sap/legaltransaction/0001|0|LegalTransaction|LglTransCategory|Legal Transaction\nsap-s4-CE_MAINTENANCENOTIFICATION_0001-v1|v4|/sap/opu/odata4/sap/api_maintenancenotification/srvd_a2x/sap/maintenancenotification/0001|0|Notification|NotificationItem|Maintenance Notification\nsap-s4-CE_MASTERWARRANTY_0001-v1|v4|/sap/opu/odata4/sap/api_masterwarranty/srvd_a2x/sap/masterwarranty/0001|0|MasterWarranty|MasterWarrantyCounter|Master Warranty\nsap-s4-CE_MATERIALVARIANTROUTING_0001-v1|v4|/sap/opu/odata4/sap/api_getmaterialvariantrouting/srvd_a2x/sap/materialvariantrouting/0001|0|MatlVarProdnRtg||Get Material Variant Production Routing\nsap-s4-CE_MEMORECORD_0001-v1|v4|/sap/opu/odata4/sap/api_memorecord/srvd_a2x/sap/memorecord/0001|0|MemoRecord||Memo Record\nsap-s4-CE_MERCHANDISECATEGORY_0002-v2|v4|/sap/opu/odata4/sap/api_merchandisecategory/srvd_a2x/sap/merchandisecategory/0002|0|MerchandiseCategory|MerchandiseCategoryText|Merchandise Category (A2X)\nsap-s4-CE_MRCHDSCATHIERARCHYNODE_0002-v2|v4|/sap/opu/odata4/sap/api_mrchdscathiernode/srvd_a2x/sap/mrchdscathierarchynode/0002|0|MCHierNodeCVRstrn|MrchdsCategoryHierarchyNode|Merchandise Category Hierarchy Node (A2X)\nsap-s4-CE_PAYFNPAYMENT_0001-v1|v4|/sap/opu/odata4/sap/api_payfnpaymentapibasic/srvd_a2x/sap/payfnpayment/0001|0|PayFnParty|PayFnPartyID|Requested Payment ― Read, Create\nsap-s4-CE_PAYMENTADVICE_0001-v1|v4|/sap/opu/odata4/sap/api_paymentadvice/srvd_a2x/sap/paymentadvice/0001|0|I_DraftAdministrativeData|I_DraftAdministrativeUser|Payment Advice\nsap-s4-CE_PHYSICALINVENTORYDOCUMENT_0001-v1|v4|/sap/opu/odata4/sap/api_physicalinventorydocument/srvd_a2x/sap/physicalinventorydocument/0001|0|BookSerialNumber|CountSerialNumber|Physical Inventory Document\nsap-s4-CE_PLANT_0001-v1|v4|/sap/opu/odata4/sap/api_plant_2/srvd_a2x/sap/plant/0001|0|Plant||Plant Configuration\nsap-s4-CE_PRELIMINARYBILLINGDOCUMENT_0001-v1|v4|/sap/opu/odata4/sap/api_preliminarybillingdocument/srvd_a2x/sap/preliminarybillingdocument/0001|0|PrelimBillgDocItemPartner|PrelimBillgDocumentItemText|Preliminary Billing Document\nsap-s4-CE_PRODCMPLNCLOGISTICSDOCUMENT_0001-v1|v4|/sap/opu/odata4/sap/api_prodcmplnclogsdocument/srvd_a2x/sap/prodcmplnclogisticsdocument/0001|1|ProdCmplncLogisticsDocItem|ProdCmplncLogisticsDocStage|Product Compliance Logistics Document\nsap-s4-CE_PRODCMPLNCLOGISTICSDOCUMENT_0002-v2|v4|/sap/opu/odata4/sap/api_prodcmplnclogsdocument/srvd_a2x/sap/prodcmplnclogisticsdocument/0002|0|PCLgsDcItmPackgInstruction|PCLgsDcMnllyOvrwrtnAddr|Product Compliance Logistics Document\nsap-s4-CE_PRODCMPLNCMLTICMPPRODUCT_0001-v1|v4|/sap/opu/odata4/sap/api_multicomponentproduct/srvd_a2x/sap/prodcmplncmlticmpproduct/0001|0|ProdCmplncMlticmpProdPrps|ProdCmplncMlticmpProduct|Multi-Component Product\nsap-s4-CE_PRODCMPLNCPHRSENABLEDFIELD_0001-v1|v4|/sap/opu/odata4/sap/api_prodcmplncphrsenabledfield/srvd_a2x/sap/prodcmplncphrsenabledfield/0001|0|ProdCmplncPhrs|ProdCmplncPhrsEnabledField|Phrase Enabled Field\nsap-s4-CE_PRODCOMPLIANCEBRANDEDPRODUCT_0001-v1|v4|/sap/opu/odata4/sap/api_prodcmplncbrnddproduct/srvd_a2x/sap/prodcompliancebrandedproduct/0001|0|ChmlCmplncBrnddProdCntry|ChmlCmplncBrnddProdName|Branded Product\nsap-s4-CE_PRODUCTCOMPLIANCEUSE_0001-v1|v4|/sap/opu/odata4/sap/api_productcomplianceuse/srvd_a2x/sap/productcomplianceuse/0001|0|ProductComplianceRecmddUse|ProductComplianceRestrictedUse|Product Compliance Uses\nsap-s4-CE_PRODUCTIONORDER_0001-v1|v4|/sap/opu/odata4/sap/api_productionorder/srvd_a2x/sap/productionorder/0001|0|PostingRule|ProductionOrder|Production Order\nsap-s4-CE_PRODUCTMARKETASSESSMENT_0001-v1|v4|/sap/opu/odata4/sap/api_productmarketassessment/srvd_a2x/sap/productmarketassessment/0001|0|ProductMarketAssessment||Product Market Assessment\nsap-s4-CE_PRODUCTSUBSTITUTIONCTRL_0002-v2|v4|/sap/opu/odata4/sap/api_prodsubstnctrl/srvd_a2x/sap/productsubstitutionctrl/0002|0|ProdSubstnCtrl|ProdSubstnCtrlGrp|Product Substitution Control\nsap-s4-CE_PRODUCT_0002-v2|v4|/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0002|0|ProdSalesDeliverySalesTax|Product|Product\nsap-s4-CE_PRODUCT_0003-v3|v4|/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0003|0|ProdSalesDeliverySalesTax|Product|Product\nsap-s4-CE_PROFITCENTER_0001-v1|v4|/sap/opu/odata4/sap/api_profitcenter/srvd_a2x/sap/profitcenter/0001|0|PrftCtrCoCodeAssignment|ProfitCenter|Profit Center\nsap-s4-CE_PROJECTBILLINGREQUEST_0001-v1|v4|/sap/opu/odata4/sap/api_projectbillingrequest/srvd_a2x/sap/projectbillingrequest/0001|0|ProjectBillingRequest|ProjectBillingRequestItem|Project Billing Request\nsap-s4-CE_PROJECTSETTLEMENTRULE_0001-v1|v4|/sap/opu/odata4/sap/api_projectsettlementrule/srvd_a2x/sap/projectsettlementrule/0001|0|ProjectSettlementRule|ProjSettlementDistrRule|Project Settlement Rule\nsap-s4-CE_PURGDOCPRICINGSIMULATION_0001-v1|v4|/sap/opu/odata4/sap/api_purgdocprcgsimulation/srvd_a2x/sap/purgdocpricingsimulation/0001|0|PurchasingDocument||Price Simulation - Read\nsap-s4-CE_RECOSTALLOCATIONACCTGNOTIF_0001-v1|v4|/sap/opu/odata4/sap/api_recostallocationacctgnotif/srvd_a2x/sap/recostallocationacctgnotif/0001|0|AcctgNotifJournalEntry|RECostAllocationAcctgNotif|Real Estate Cost Allocation Accounting Notification ‒ Post Journal Entry\nsap-s4-CE_REGULATORYDATAASSESSMENT_0001-v1|v4|/sap/opu/odata4/sap/api_regulatorydataassessment/srvd_a2x/sap/regulatorydataassessment/0001|0|RegulatoryDataAssessment|RegulatoryDataDetail1|Regulatory Data Assessment\nsap-s4-CE_RETAILPROMOTION_0001-v1|v4|/sap/opu/odata4/sap/api_retailpromotion/srvd_a2x/sap/retailpromotion/0001|0|RetailPromotion|RetailPromotionItem|Retail Promotion (A2X)\nsap-s4-CE_RETAILSEASON_0001-v1|v4|/sap/opu/odata4/sap/api_retailseason/srvd_a2x/sap/api_retailseason/0001|1|ProductSeason|Season|Retail Season (A2X)\nsap-s4-CE_RETAILSEASON_0002-v1|v4|/sap/opu/odata4/sap/api_retailseason/srvd_a2x/sap/retailseason/0001|0|ProductSeason|Season|Retail Season (A2X)\nsap-s4-CE_SALESCONTRACT_0001-v1|v4|/sap/opu/odata4/sap/api_salescontract/srvd_a2x/sap/salescontract/0001|0|SalesContract|SalesContractItem|Sales Contract (A2X)\nsap-s4-CE_SALESORDERSIMULATION_0001-v1|v4|/sap/opu/odata4/sap/api_salesordersimulation/srvd_a2x/sap/salesordersimulation/0001|0|SalesOrder|SalesOrderItem|Sales Order - Simulate (A2X)\nsap-s4-CE_SALESORDERWITHOUTCHARGE_0001-v1|v4|/sap/opu/odata4/sap/api_salesorderwithoutcharge/srvd_a2x/sap/salesorderwithoutcharge/0001|0|SalesOrderWithoutCharge|SalesOrderWithoutChargeItem|Sales Order Without Charge (A2X)\n'
}
private static String svcChunk2() {
    return 'sap-s4-CE_SALESPRICE_0002-v2|v4|/sap/opu/odata4/sap/api_salesprice/srvd_a2x/sap/salesprice/0002|0|SalesPrice||Sales Price - Retrieve (A2X)\nsap-s4-CE_SECURITYCLASS_0002-v2|v4|/sap/opu/odata4/sap/api_securityclass/srvd_a2x/sap/securityclass/0002|0|BondRdmptnSchedCalcParam|BondRdmptnSchedCndnFmla|Security Class\nsap-s4-CE_SERVDOCSSETTLEMENTRULE_0001-v1|v4|/sap/opu/odata4/sap/api_servdocs_settlement_rule/srvd_a2x/sap/servdocssettlementrule/0001|0|SrvcDocItemDistributionRule|SrvcDocumentItemSettlementRule|Service Document Item Settlement Rule\nsap-s4-CE_SERVICEENTRYSHEET_0001-v1|v4|/sap/opu/odata4/sap/api_serviceentrysheet/srvd_a2x/sap/serviceentrysheet/0001|0|AccountAssignment|ServiceEntrySheet|Service Entry Sheet (Lean Services)\nsap-s4-CE_SLSMATLDETERMINATIONRECORD_0001-v1|v4|/sap/opu/odata4/sap/api_slsmatldeterminationrecord/srvd_a2x/sap/slsmatldeterminationrecord/0001|0|SlsMatlDeterminationRecord|SlsMatlDetnRecdAddlSubstit|Condition Record for Material Determination in Sales (A2X)\nsap-s4-CE_SLSMATLLSTGOREXCLSNRECORD_0001-v1|v4|/sap/opu/odata4/sap/api_slsmatllstgorexclsnrecord/srvd_a2x/sap/slsmatllstgorexclsnrecord/0001|0|SlsMatlLstgOrExclsnRecord||Condition Record for Sales Material Listing and Exclusion (A2X)\nsap-s4-CE_STATISTICALKEYFIGUREDOCUMENT_0001-v1|v4|/sap/opu/odata4/sap/api_ststclkeyfiguredocument/srvd_a2x/sap/statisticalkeyfiguredocument/0001|0|Document|Item|Statistical Key Figure Values ‒ Read, Create, Reverse\nsap-s4-CE_STATISTICALKEYFIGURE_0001-v1|v4|/sap/opu/odata4/sap/api_statisticalkeyfigure/srvd_a2x/sap/statisticalkeyfigure/0001|0|StatisticalKeyFigure|StatisticalKeyFigureText|Statistical Key Figure\nsap-s4-CE_STOCKTRANSPORTORDER_0001-v1|v4|/sap/opu/odata4/sap/api_stocktransportorder/srvd_a2x/sap/stocktransportorder/0001|0|STOAccountAssignment|StockTransportOrder|Stock Transport Order\nsap-s4-CE_STORAGECLASSASSESSMENT_0001-v1|v4|/sap/opu/odata4/sap/api_storageclass/srvd_a2x/sap/storageclassassessment/0001|0|StorageClassAssessment|StorageClassClassification|Storage Class Assessment\nsap-s4-CE_TOXICOLOGICALPROPERTY_0001-v1|v4|/sap/opu/odata4/sap/api_toxicologicalproperty/srvd_a2x/sap/toxicologicalproperty/0001|0|PCTxAcuteDermalToxtyAssmt|PCTxAcuteDermalToxtyStudy|Toxicological Information\nsap-s4-CE_WHSE_PREALLOCATEDSTOCK_0001-v1|v4|/sap/opu/odata4/sap/api_whse_preallocatedstock/srvd_a2x/sap/whse_preallocatedstock/0001|0|WhsePreallocatedStock||Warehouse Preallocated Stock (A2X)\nsap-s4-CE_WRHSMGMTCONTROLCYCLE_0001-v1|v4|/sap/opu/odata4/sap/api_wrhsmgmtcontrolcycle/srvd_a2x/sap/wrhsmgmtcontrolcycle/0001|0|WrhsMgmtControlCycle||Warehouse Management Control Cycle\nsap-s4-REPETITIVEMFGCONFIRMATION_0001-v1|v4|/sap/opu/odata4/sap/api_rptvmfgconfirmation/srvd_a2x/sap/repetitivemfgconfirmation/0001|0|RepetitiveMfgConfirmation|RptvMfgConfGRBatchCharc|Repetitive Manufacturing Confirmation'
}
