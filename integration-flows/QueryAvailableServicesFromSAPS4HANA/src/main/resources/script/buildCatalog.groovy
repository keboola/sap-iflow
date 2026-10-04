import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.mapping.ValueMappingApi
import com.sap.it.api.securestore.SecureStoreService
import groovy.json.JsonSlurper

static String readCfg(Message message, String key) {
    def v = message.getProperty(key)?.toString()?.trim()
    if (!v || v.contains("{{")) { return null }
    return v
}

static int toInt(String v, int dflt) {
    try { return (v != null && v.isInteger()) ? v.toInteger() : dflt } catch (Exception ignored) { return dflt }
}

static Object parseJson(Object source) {
    if (source instanceof Reader) { return new JsonSlurper().parse((Reader) source) }
    String text = source?.toString()
    if (!text) { throw new IllegalArgumentException("The JSON input text should neither be null nor empty.") }
    return new JsonSlurper().parse(new StringReader(text))
}

// Service names
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

// API_PRODUCT_0002_G4BA -> [base: API_PRODUCT, version: 0002]; no version means 0001
static Map serviceKey(String id, String proto) {
    String base = id.replaceAll("_" + proto + '$', "").replaceAll("_+\$", "")
    def m = (base =~ /_(\d{4})$/)
    String version = "0001"
    if (m.find()) {
        version = m.group(1)
        base = base.substring(0, m.start())
    }
    return [base: base.toUpperCase(), version: version]
}

// Value Mapping
static String mapped(Object api, String name, String target) {
    if (api == null || !name) { return null }
    try { return api.getMappedValue("Keboola", "SERVICE_NAME", name, "Keboola", target) } catch (Exception ignored) { return null }
}

// The address of a name: <base>_<version> first, then the plain name, which holds the lowest version
static Map lookup(Object api, String base, String version) {
    def keys = (version && version != "0001") ? [base + "_" + version, base] : [base]
    for (key in keys) {
        def path = mapped(api, key, "SERVICE_PATH")
        if (path) { return [path: path, title: mapped(api, key, "SERVICE_TITLE"), key: key] }
    }
    return null
}

static String mappedTitle(Object api, String base, String version) {
    def keys = (version && version != "0001") ? [base + "_" + version, base] : [base]
    for (key in keys) {
        def title = mapped(api, key, "SERVICE_TITLE")
        if (title) { return title }
    }
    return null
}

// The four naming rules: group api_x -> service x, api_x, apix, x without underscores
static List candidatesFor(String group, String version) {
    String grp = group.toLowerCase()
    String x = grp.startsWith("api_") ? grp.substring(4) : grp
    def names = []
    [x, grp, grp.replace("_", ""), x.replace("_", "")].each { if (it && !names.contains(it)) { names << it } }
    return names.collect { "/sap/opu/odata4/sap/" + grp + "/srvd_a2x/sap/" + it + "/" + version }
}

static Map row(String host, String name, String title, String path) {
    return [ID: name, Title: name, Description: title, ServiceUrl: host + path, MetadataUrl: host + path + "/\$metadata"]
}

// Communication arrangements
static Map resolveArrangements(List arrs, List inbound, List users, String userName, Object api,
                               String resolve, String host, List notes) {
    def scenarioOf = [:]
    arrs.each { a -> scenarioOf[a.uuid] = a.scenario ?: "" }

    // The sign-in user's arrangements, by user name or OAuth client id; none known = every arrangement
    def allowed = null
    if (userName) {
        allowed = users.findAll { userName.equalsIgnoreCase(it.user?.toString()) || userName.equalsIgnoreCase(it.client?.toString()) }
                       .collect { it.uuid } as Set
        if (allowed.isEmpty()) {
            allowed = null
            notes << "user filter: the sign-in user is not among the inbound users of any arrangement; every arrangement's services are listed"
        }
    } else {
        notes << "user filter: no user name for this sign-in method; every arrangement's services are listed"
    }

    def byPath = [:]
    def candidates = []
    def unlisted = []
    def seenV4 = [] as Set
    int v4Tenant = 0, v4Mapped = 0
    def tenantV4 = [] as Set
    inbound.each { r ->
        if (r.hidden || !r.id) { return }
        if (r.type == "G4BA" && tenantV4.add(r.id)) {
            v4Tenant++
            def k = serviceKey(r.id, "G4BA")
            if (lookup(api, k.base, k.version) != null) { v4Mapped++ }
        }
        if (allowed != null && !allowed.contains(r.uuid)) { return }
        def scenario = scenarioOf[r.uuid] ?: ""
        if (r.type == "IWSG") {
            def k = serviceKey(stripServiceSuffix(r.id, "IWSG"), "IWSG")
            def hit = lookup(api, k.base, k.version)
            def path = hit != null ? hit.path : "/sap/opu/odata/sap/" + k.base
            def title = (hit != null ? hit.title : null) ?: mappedTitle(api, k.base, k.version) ?: (scenario ? "via " + scenario : k.base)
            byPath[path] = [name: k.base, title: title, path: path]
        } else if (r.type == "G4BA") {
            def k = serviceKey(r.id, "G4BA")
            if (!seenV4.add(k.base + "|" + k.version)) { return }
            String name = k.version == "0001" ? k.base : k.base + "_" + k.version
            if (resolve == "off") { unlisted << name; return }
            def hit = lookup(api, k.base, k.version)
            if (hit != null) {
                byPath[hit.path] = [name: name, title: hit.title ?: (scenario ? "via " + scenario : name), path: hit.path]
            } else if (resolve == "lookup+verify") {
                candidatesFor(k.base, k.version).each { p ->
                    candidates << [name: name, title: scenario ? "via " + scenario : name, path: p]
                }
            } else {
                unlisted << name
            }
        }
    }
    def rows = byPath.values().toList().sort { it.name }.collect { row(host, it.name, it.title, it.path) }
    def stats = [arrangements: arrs.size(), inboundServices: inbound.size(), inboundUsers: users.size(),
                 filtered: allowed != null, listed: rows.size(), v4OnTenant: v4Tenant, v4Mapped: v4Mapped,
                 v4ToVerify: candidates.collect { it.name }.unique().size(), v4Unlisted: unlisted.size()]
    notes << ("arrangements: " + arrs.size() + " arrangements, " + inbound.size() + " inbound services, " +
              (allowed != null ? allowed.size() + " assigned to the sign-in user" : "not filtered by user") +
              "; V4 on the tenant: " + v4Tenant + " distinct, " + v4Mapped + " in the value mapping")
    return [rows: rows, candidates: candidates, unlisted: unlisted, stats: stats]
}

// Gateway catalogues
static List gatewayRowsOf(String body) {
    try {
        def j = parseJson(body)
        if (j instanceof Map && j["d"] instanceof Map && j["d"]["results"] instanceof List) {
            return j["d"]["results"].findAll { it instanceof Map }
        }
    } catch (Exception ignored) { }
    return null
}

static List v4CatalogEntries(Object body, Object api, List notes) {
    def out = []
    def seen = [] as Set
    def j
    try { j = parseJson(body) } catch (Exception e) {
        notes << ("gateway v4: unparseable response, ignored (" + (e.getMessage() ?: "") + ")")
        return null
    }
    if (!(j instanceof Map) || !(j["value"] instanceof List)) { return null }
    j["value"].each { g ->
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
                out << [name: id, title: title, path: path, derived: false]
                return
            }
            def hit = groupId ? lookup(api, groupId.toUpperCase(), version) : null
            if (hit != null) {
                out << [name: id, title: title, path: hit.path, derived: false]
                return
            }
            def path = "/sap/opu/odata4/sap/" + (groupId ?: id).toLowerCase() + "/srvd_a2x/sap/" + id.toLowerCase() + "/" + version
            out << [name: id, title: title + " (address derived, unverified)", path: path, derived: true]
        }
    }
    return out
}

// Answers that mean the address is not the API: a redirect, a 404, or 200 with no JSON
static boolean looksLikeWrongHost(String signature) {
    return signature == "NOT_JSON" || signature == "404" || (signature ==~ /^3\d\d$/)
}

def Message processData(Message message) {
    def messageLog = messageLogFactory.getMessageLog(message)
    // The notes list is shared by every step; an empty list is falsy in Groovy, so never "?: []"
    def notes = message.getProperty("CATALOG_NOTES")
    if (!(notes instanceof List)) { notes = []; message.setProperty("CATALOG_NOTES", notes) }
    String host = message.getProperty("CATALOG_HOST")?.toString() ?: ""
    String sourceMode = message.getProperty("CATALOG_SOURCE_MODE")?.toString() ?: "auto"
    String resolveMode = message.getProperty("CATALOG_RESOLVE_MODE")?.toString() ?: "lookup+verify"
    int verifyLimit = toInt(message.getProperty("CATALOG_VERIFY_LIMIT_N")?.toString(), 20)
    boolean triedArrangements = message.getProperty("CATALOG_TRY_ARRANGEMENTS")?.toString() == "true"
    String arrOutcome = message.getProperty("ARR_OUTCOME")?.toString() ?: ""
    String arrFirst = message.getProperty("ARR_FIRST_STATUS")?.toString() ?: ""
    boolean gatewayRan = message.getProperty("GW_RAN")?.toString() == "true"
    int v2code = gatewayRan ? toInt(message.getProperty("GW_V2_STATUS")?.toString(), 0) : 0
    int v4code = gatewayRan ? toInt(message.getHeaders().get("CamelHttpResponseCode")?.toString(), 0) : 0

    // Refusals that need no listing
    if (arrFirst == "401" || v2code == 401 || v4code == 401) {
        throw new IllegalStateException("UPSTREAM: SAP refused the sign-in (HTTP 401). Check the security " +
            "material named in S4_CREDENTIAL_ALIAS against S4_AUTH_METHOD, and that the user is not locked in SAP.")
    }
    if (arrOutcome == "DEADLINE") {
        throw new IllegalStateException("Reading the communication arrangements of " + host + " timed out: " +
            message.getProperty("ARR_CALLS") + " calls did not finish within the 35-second deadline of one read.")
    }

    // The address book
    def api = null
    if (resolveMode != "off") {
        try { api = ITApiFactory.getApi(ValueMappingApi.class, null) } catch (Exception e) {
            notes << ("value mapping: not available (" + (e.getMessage() ?: e.getClass().getSimpleName()) + ")")
        }
        if (api != null && mapped(api, "API_BUSINESS_PARTNER", "SERVICE_TITLE") == null) {
            notes << "value mapping KeboolaServiceAddresses: not deployed or empty, every lookup answers nothing"
        }
    }

    // The sign-in user, for the filter only; never logged
    String userName = null
    def authMethod = (readCfg(message, "CFG_AUTH_METHOD") ?: "OAuth2 Client Credentials").toLowerCase()
    def alias = readCfg(message, "CFG_CREDENTIAL_ALIAS")
    if (alias && !(authMethod in ["client certificate", "none"])) {
        try {
            userName = ITApiFactory.getService(SecureStoreService.class, null)?.getUserCredential(alias)?.getUsername()
        } catch (Exception ignored) { userName = null }
    }

    // The list
    def rows = []
    def candidates = []
    def unlisted = []
    def stats = [:]
    String source = null
    String v2sig = gatewayRan ? v2code.toString() : "-"
    String v4sig = gatewayRan ? v4code.toString() : "-"
    if (triedArrangements && arrOutcome == "OK") {
        def r = resolveArrangements(message.getProperty("ARR_ARRANGEMENTS") ?: [], message.getProperty("ARR_INBOUND") ?: [],
                                    message.getProperty("ARR_USERS") ?: [], userName, api, resolveMode, host, notes)
        rows = r.rows; candidates = r.candidates; unlisted = r.unlisted; stats = r.stats
        source = "arrangements"
    } else if (gatewayRan) {
        if (v2code == 200) {
            def v2rows = gatewayRowsOf(message.getProperty("GW_V2_BODY")?.toString() ?: "")
            if (v2rows != null) {
                rows.addAll(v2rows)
                notes << ("gateway v2: " + v2rows.size() + " catalogue rows")
            } else {
                v2sig = "NOT_JSON"
                notes << "gateway v2: HTTP 200 but not the catalogue, ignored"
            }
        } else {
            notes << ("gateway v2: HTTP " + v2code)
        }
        if (v4code == 200) {
            def entries = v4CatalogEntries(message.getBody(java.io.Reader), api, notes)
            if (entries != null) {
                entries.each { e -> rows << row(host, e.name, e.title, e.path) }
                notes << ("gateway v4: " + entries.size() + " services, " + entries.count { it.derived } + " with a derived address")
            } else {
                v4sig = "NOT_JSON"
                notes << "gateway v4: HTTP 200 but not the catalogue, ignored"
            }
        } else {
            notes << ("gateway v4: HTTP " + v4code)
        }
        if (rows) { source = "gateway" }
    }

    // Nothing readable: name the reason, never an empty 200
    if (source == null) {
        String answers = "the communication arrangement API answered " + (triedArrangements ? "HTTP " + arrFirst : "(not read)") +
                         ", the Gateway catalogues " + (gatewayRan ? "HTTP " + v2sig + " (V2) and HTTP " + v4sig + " (V4)" : "(not read)")
        if (sourceMode == "arrangements") {
            throw new IllegalStateException("UPSTREAM: The communication arrangements (communication scenario " +
                "SAP_COM_0A07) are not readable on " + host + ": " + answers + ". On SAP S/4HANA Cloud assign " +
                "SAP_COM_0A07 to the communication user's arrangement; on another system set CATALOG_SOURCE to auto or gateway.")
        }
        boolean arrWrong = !triedArrangements || looksLikeWrongHost(arrFirst)
        boolean gwWrong = gatewayRan && looksLikeWrongHost(v2sig) && looksLikeWrongHost(v4sig)
        if (arrWrong && gwWrong) {
            throw new IllegalStateException("CONFIG: S4_HOSTNAME is not the API address of the system: " + answers +
                ", as a web front end or a redirecting address does. Enter the API address, for example " +
                "https://my123456-api.s4hana.cloud.sap, on " + host + ".")
        }
        if (triedArrangements && arrFirst == "403" && v2code == 403 && v4code == 403) {
            throw new IllegalStateException("UPSTREAM: The communication arrangements (communication scenario " +
                "SAP_COM_0A07) are not readable on " + host + " and its Gateway catalogues are not exposed: " + answers +
                ". On SAP S/4HANA Cloud assign SAP_COM_0A07 to the communication user's arrangement.")
        }
        boolean serverError = (triedArrangements && arrFirst ==~ /^5\d\d$/) || v2code >= 500 || v4code >= 500
        throw new IllegalStateException("UPSTREAM: " + (serverError ? "SAP answered the catalogue requests with a server error" :
            "The Gateway catalogue is not readable") + " on " + host + ": " + answers + ".")
    }

    // Hand over: rows, the verification queue, the names left out
    boolean verify = resolveMode == "lookup+verify" && verifyLimit > 0 && !candidates.isEmpty()
    if (!verify && !candidates.isEmpty()) {
        candidates.collect { it.name }.unique().each { unlisted << it }
        candidates = []
    }
    message.setProperty("CATALOG_ROWS", rows)
    message.setProperty("CATALOG_SOURCE_USED", source)
    message.setProperty("CATALOG_UNLISTED", unlisted.unique())
    message.setProperty("CATALOG_STATS", stats)
    message.setProperty("VERIFY_QUEUE", candidates)
    message.setProperty("VERIFY_INDEX", "0")
    message.setProperty("VERIFY_CALLS", "0")
    message.setProperty("VERIFY_RESOLVED", [:])
    message.setProperty("VERIFY_MORE", verify ? "true" : "false")
    if (verify) {
        notes << ("verify: " + candidates.collect { it.name }.unique().size() + " unmapped V4 service(s), " +
                  candidates.size() + " candidate addresses, at most " + verifyLimit + " calls")
    }
    message.setBody("")
    if (messageLog != null) {
        messageLog.setStringProperty("CatalogSource", source)
    }
    return message
}
