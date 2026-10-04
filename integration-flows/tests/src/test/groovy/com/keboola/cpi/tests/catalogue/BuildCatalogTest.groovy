package com.keboola.cpi.tests.catalogue

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.mapping.ValueMappingApi
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.securestore.UserCredential
import org.junit.After
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// buildCatalog.groovy: the list from the arrangements or the Gateway catalogues, the Value
// Mapping lookups, the candidates by the four naming rules, the user filter, the wrong-host guard.
class BuildCatalogTest {

    static final String HOST = "https://s4.example.com"

    // A slice of the shipped value mapping: name -> path, name -> title, one pair per group
    static final Map MAPPING = [
        "API_PRODUCT|SERVICE_PATH"             : "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0001",
        "API_PRODUCT|SERVICE_TITLE"            : "Product (A2X)",
        "API_PRODUCT_0002|SERVICE_PATH"        : "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0002",
        "API_PRODUCT_0002|SERVICE_TITLE"       : "Product, version 0002",
        "API_COST_CENTER|SERVICE_PATH"         : "/sap/opu/odata4/sap/api_cost_center/srvd_a2x/sap/costcenter/0001",
        "API_COST_CENTER|SERVICE_TITLE"        : "Cost Center",
        "API_BUSINESS_PARTNER|SERVICE_TITLE"   : "Business Partner (A2X)",
        "API_MANAGE_SKILLTAGS_SRV|SERVICE_PATH": "/sap/opu/odata/SHCM/API_MANAGE_SKILLTAGS_SRV",
        "API_MANAGE_SKILLTAGS_SRV|SERVICE_TITLE": "Workforce Person SkillTag.",
    ]

    Script script

    @Before
    void load() {
        script = Scripts.load(Scripts.CATALOGUE, "buildCatalog")
        ITApiFactory.register(ValueMappingApi, [getMappedValue: { a, i, v, ta, ti -> MAPPING[v + "|" + ti] }] as ValueMappingApi)
        ITApiFactory.register(SecureStoreService, [getUserCredential: { alias -> new UserCredential("KEBOOLA_USER", "x".toCharArray()) }] as SecureStoreService)
    }

    @After
    void reset() {
        ITApiFactory.reset()
    }

    static Map arrangement(String uuid, String scenario) { return [uuid: uuid, scenario: scenario] }
    static Map inbound(String uuid, String id, String type, boolean hidden = false) { return [uuid: uuid, id: id, type: type, hidden: hidden] }
    static Map user(String uuid, String name, String client = "") { return [uuid: uuid, user: name, client: client] }

    static List ARRANGEMENTS = [arrangement("a1", "SAP_COM_0008"), arrangement("a2", "SAP_COM_0009"), arrangement("a3", "SAP_COM_0943")]
    static List USERS = [user("a1", "KEBOOLA_USER"), user("a2", "KEBOOLA_USER"), user("a3", "SOMEBODY_ELSE", "sb-client")]
    static List INBOUND = [
        inbound("a1", "API_BUSINESS_PARTNER_0001_IWSG", "IWSG"),
        inbound("a1", "API_MANAGE_SKILLTAGS_SRV_0001_IWSG", "IWSG"),
        inbound("a1", "API_GLACCOUNTINCHARTOFACCOUNTS_SRV_0001_", "IWSG"),
        inbound("a1", "DEBMAS_IDOC", "IDOC"),
        inbound("a2", "API_PRODUCT_G4BA", "G4BA"),
        inbound("a2", "API_PRODUCT_0002_G4BA", "G4BA"),
        inbound("a2", "API_BUS_SITN_MSTRDATA_G4BA", "G4BA"),
        inbound("a2", "HIDDEN_ONE_G4BA", "G4BA", true),
        inbound("a3", "API_COST_CENTER_G4BA", "G4BA"),
    ]

    Message run(Map overrides = [:]) {
        def props = [
            CATALOG_HOST: HOST, CATALOG_SOURCE_MODE: "auto", CATALOG_RESOLVE_MODE: "lookup+verify",
            CATALOG_VERIFY_LIMIT_N: "20", CATALOG_TRY_ARRANGEMENTS: "true", ARR_OUTCOME: "OK",
            ARR_FIRST_STATUS: "200", GW_RAN: "false", CATALOG_NOTES: [], CFG_AUTH_METHOD: "Basic",
            CFG_CREDENTIAL_ALIAS: "KEBOOLA_S4", ARR_ARRANGEMENTS: ARRANGEMENTS, ARR_INBOUND: INBOUND, ARR_USERS: USERS,
        ] + overrides
        def m = message(properties: props, headers: [:], body: "")
        script.processData(m)
        return m
    }

    static List names(Message m) { return m.getProperty("CATALOG_ROWS").collect { it.ID } }

    static Map rowOf(Message m, String name) { return m.getProperty("CATALOG_ROWS").find { it.ID == name } }

    @Test
    void namesStripTheArrangementSuffixes() {
        assert script.serviceKey("API_PRODUCT_G4BA", "G4BA") == [base: "API_PRODUCT", version: "0001"]
        assert script.serviceKey("API_SLSPRCGCONDITIONFIELD_0001_G4BA", "G4BA") == [base: "API_SLSPRCGCONDITIONFIELD", version: "0001"]
        assert script.serviceKey("API_PRODUCT_0002_G4BA", "G4BA") == [base: "API_PRODUCT", version: "0002"]
        assert script.stripServiceSuffix("API_BUSINESS_PARTNER_0001_IWSG", "IWSG") == "API_BUSINESS_PARTNER"
        assert script.stripServiceSuffix("API_CNSLDTNADHOCITEM_IWSG", "IWSG") == "API_CNSLDTNADHOCITEM"
        // an id truncated by SAP at 40 characters keeps its trailing underscore
        assert script.stripServiceSuffix("API_GLACCOUNTINCHARTOFACCOUNTS_SRV_0001_", "IWSG") == "API_GLACCOUNTINCHARTOFACCOUNTS_SRV"
    }

    @Test
    void candidatesFollowTheFourNamingRules() {
        assert script.candidatesFor("API_PRODUCT", "0001") == [
            "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0001",
            "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/api_product/0001",
            "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/apiproduct/0001"]
        assert script.candidatesFor("API_BUS_SITN_MSTRDATA", "0002") == [
            "/sap/opu/odata4/sap/api_bus_sitn_mstrdata/srvd_a2x/sap/bus_sitn_mstrdata/0002",
            "/sap/opu/odata4/sap/api_bus_sitn_mstrdata/srvd_a2x/sap/api_bus_sitn_mstrdata/0002",
            "/sap/opu/odata4/sap/api_bus_sitn_mstrdata/srvd_a2x/sap/apibussitnmstrdata/0002",
            "/sap/opu/odata4/sap/api_bus_sitn_mstrdata/srvd_a2x/sap/bussitnmstrdata/0002"]
        assert script.candidatesFor("ZMY_GROUP", "0001") == [
            "/sap/opu/odata4/sap/zmy_group/srvd_a2x/sap/zmy_group/0001",
            "/sap/opu/odata4/sap/zmy_group/srvd_a2x/sap/zmygroup/0001"]
        assert script.candidatesFor("API_PRODUCT", "0001").size() <= 4
    }

    @Test
    void lookupTriesTheVersionKeyThenThePlainName() {
        def api = ITApiFactory.getApi(ValueMappingApi, null)
        assert script.lookup(api, "API_PRODUCT", "0001").path.endsWith("/product/0001")
        assert script.lookup(api, "API_PRODUCT", "0002").path.endsWith("/product/0002")
        assert script.lookup(api, "API_PRODUCT", "0003").path.endsWith("/product/0001")
        assert script.lookup(api, "API_UNKNOWN", "0001") == null
        assert script.lookup(null, "API_PRODUCT", "0001") == null
        assert script.mapped(api, "API_PRODUCT", "SERVICE_TITLE") == "Product (A2X)"
    }

    // Arrangements: V2 rows, mapped V4 rows, candidates, the user filter
    @Test
    void arrangementsGiveTheUsersServicesWithAddressesAndTitles() {
        def m = run()
        assert m.getProperty("CATALOG_SOURCE_USED") == "arrangements"
        assert names(m) == ["API_BUSINESS_PARTNER", "API_GLACCOUNTINCHARTOFACCOUNTS_SRV", "API_MANAGE_SKILLTAGS_SRV", "API_PRODUCT", "API_PRODUCT_0002"]
        // V2 rule: /sap/opu/odata/sap/<name>, title from the mapping, "via <scenario>" without one
        assert rowOf(m, "API_BUSINESS_PARTNER").ServiceUrl == HOST + "/sap/opu/odata/sap/API_BUSINESS_PARTNER"
        assert rowOf(m, "API_BUSINESS_PARTNER").Description == "Business Partner (A2X)"
        assert rowOf(m, "API_BUSINESS_PARTNER").MetadataUrl == HOST + "/sap/opu/odata/sap/API_BUSINESS_PARTNER/\$metadata"
        assert rowOf(m, "API_GLACCOUNTINCHARTOFACCOUNTS_SRV").Description == "via SAP_COM_0008"
        // a V2 service outside /sap/opu/odata/sap/ gets its mapped address
        assert rowOf(m, "API_MANAGE_SKILLTAGS_SRV").ServiceUrl == HOST + "/sap/opu/odata/SHCM/API_MANAGE_SKILLTAGS_SRV"
        // V4: address and title from the mapping, versions apart
        assert rowOf(m, "API_PRODUCT").ServiceUrl == HOST + "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0001"
        assert rowOf(m, "API_PRODUCT").Description == "Product (A2X)"
        assert rowOf(m, "API_PRODUCT_0002").ServiceUrl.endsWith("/product/0002")
        // the unmapped V4 name goes to the verification queue, with the four candidates
        def queue = m.getProperty("VERIFY_QUEUE")
        assert queue.collect { it.name }.unique() == ["API_BUS_SITN_MSTRDATA"]
        assert queue.size() == 4
        assert queue[0].path == "/sap/opu/odata4/sap/api_bus_sitn_mstrdata/srvd_a2x/sap/bus_sitn_mstrdata/0001"
        assert queue[0].title == "via SAP_COM_0009"
        assert m.getProperty("VERIFY_MORE") == "true"
        assert m.getProperty("CATALOG_UNLISTED") == []
        // the user filter: another user's cost center service is not listed, the hidden row neither
        assert !names(m).contains("API_COST_CENTER")
        assert !names(m).contains("HIDDEN_ONE")
        def stats = m.getProperty("CATALOG_STATS")
        assert stats.filtered == true
        assert stats.v4OnTenant == 4 && stats.v4Mapped == 3
        assert m.getBody().toString() == ""
    }

    @Test
    void theUserFilterMatchesTheOAuthClientIdToo() {
        ITApiFactory.register(SecureStoreService, [getUserCredential: { alias -> new UserCredential("sb-client", "x".toCharArray()) }] as SecureStoreService)
        def m = run(CFG_AUTH_METHOD: "OAuth2 Client Credentials")
        assert names(m) == ["API_COST_CENTER"]
    }

    @Test
    void anUnknownUserListsEveryArrangementWithANote() {
        ITApiFactory.register(SecureStoreService, [getUserCredential: { alias -> new UserCredential("NOBODY", "x".toCharArray()) }] as SecureStoreService)
        def m = run()
        assert names(m).containsAll(["API_BUSINESS_PARTNER", "API_COST_CENTER", "API_PRODUCT"])
        assert m.getProperty("CATALOG_STATS").filtered == false
        assert m.getProperty("CATALOG_NOTES").any { it.contains("not among the inbound users") }
        assert !m.getProperty("CATALOG_NOTES").join(" ").contains("NOBODY")
    }

    @Test
    void aClientCertificateHasNoUserNameSoNothingIsFiltered() {
        def m = run(CFG_AUTH_METHOD: "Client Certificate")
        assert names(m).contains("API_COST_CENTER")
        assert m.getProperty("CATALOG_NOTES").any { it.contains("no user name for this sign-in method") }
    }

    @Test
    void anUnreadableSecureStoreMeansNoFilter() {
        ITApiFactory.register(SecureStoreService, [getUserCredential: { alias -> throw new IllegalStateException("no such alias") }] as SecureStoreService)
        def m = run()
        assert names(m).contains("API_COST_CENTER")
    }

    // The resolve modes
    @Test
    void lookupOnlyLeavesUnmappedNamesUnlisted() {
        def m = run(CATALOG_RESOLVE_MODE: "lookup")
        assert names(m).contains("API_PRODUCT")
        assert m.getProperty("VERIFY_QUEUE") == []
        assert m.getProperty("VERIFY_MORE") == "false"
        assert m.getProperty("CATALOG_UNLISTED") == ["API_BUS_SITN_MSTRDATA"]
    }

    @Test
    void offLeavesEveryV4NameUnlisted() {
        def m = run(CATALOG_RESOLVE_MODE: "off")
        assert names(m) == ["API_BUSINESS_PARTNER", "API_GLACCOUNTINCHARTOFACCOUNTS_SRV", "API_MANAGE_SKILLTAGS_SRV"]
        assert m.getProperty("CATALOG_UNLISTED") == ["API_PRODUCT", "API_PRODUCT_0002", "API_BUS_SITN_MSTRDATA"]
        assert m.getProperty("VERIFY_MORE") == "false"
    }

    @Test
    void aVerifyLimitOfZeroSkipsTheCalls() {
        def m = run(CATALOG_VERIFY_LIMIT_N: "0")
        assert m.getProperty("VERIFY_MORE") == "false"
        assert m.getProperty("CATALOG_UNLISTED") == ["API_BUS_SITN_MSTRDATA"]
    }

    @Test
    void anUndeployedMappingIsNoted() {
        ITApiFactory.register(ValueMappingApi, [getMappedValue: { a, i, v, ta, ti -> null }] as ValueMappingApi)
        def m = run()
        assert m.getProperty("CATALOG_NOTES").any { it.contains("not deployed or empty") }
        assert m.getProperty("VERIFY_QUEUE").collect { it.name }.unique() == ["API_PRODUCT", "API_PRODUCT_0002", "API_BUS_SITN_MSTRDATA"]
        assert rowOf(m, "API_BUSINESS_PARTNER").Description == "via SAP_COM_0008"
    }

    // The Gateway catalogues
    static final String V2_BODY = '{"d":{"results":[' +
        '{"ID":"ZAPI_BUSINESS_PARTNER_0001","Title":"API_BUSINESS_PARTNER","Description":"Business Partner","ServiceUrl":"https://internal:44300/sap/opu/odata/sap/API_BUSINESS_PARTNER","MetadataUrl":"https://internal:44300/sap/opu/odata/sap/API_BUSINESS_PARTNER/$metadata","ServiceType":"WEB_API","IsSapService":"true"},' +
        '{"ID":"ZUI_THING_0001","Title":"UI_THING","Description":"Fiori","ServiceUrl":"https://internal:44300/sap/opu/odata/sap/UI_THING","ServiceType":"UI","IsSapService":"true"}]}}'
    static final String V4_BODY = '{"value":[' +
        '{"GroupId":"API_COST_CENTER","DefaultSystem":{"Services":[{"ServiceId":"API_COST_CENTER_SRV","ServiceVersion":"0001","Description":"Cost Center","ServiceUrl":"https://internal:44300/sap/opu/odata4/sap/api_cost_center/srvd_a2x/sap/costcenter/0001"}]}},' +
        '{"GroupId":"API_PRODUCT","DefaultSystem":{"Services":[{"ServiceId":"PRODUCT","ServiceVersion":"0001","Description":"Product"}]}},' +
        '{"GroupId":"ZMY_GROUP","DefaultSystem":{"Services":[{"ServiceId":"ZMY_SRV","ServiceVersion":"0001","Description":"Mine"}]}}]}'

    Message gateway(String arrFirst, int v2, String v2Body, int v4, String v4Body, Map overrides = [:]) {
        def props = [
            CATALOG_HOST: HOST, CATALOG_SOURCE_MODE: "auto", CATALOG_RESOLVE_MODE: "lookup+verify",
            CATALOG_VERIFY_LIMIT_N: "20", CATALOG_TRY_ARRANGEMENTS: arrFirst != null ? "true" : "false",
            ARR_OUTCOME: arrFirst != null ? "HTTP_" + arrFirst : "", ARR_FIRST_STATUS: arrFirst ?: "",
            GW_RAN: "true", GW_V2_STATUS: v2.toString(), GW_V2_BODY: v2Body, CATALOG_NOTES: [],
            CFG_AUTH_METHOD: "Basic", CFG_CREDENTIAL_ALIAS: "KEBOOLA_S4",
            ARR_ARRANGEMENTS: [], ARR_INBOUND: [], ARR_USERS: [],
        ] + overrides
        def m = message(properties: props, headers: [CamelHttpResponseCode: v4], body: v4Body)
        script.processData(m)
        return m
    }

    @Test
    void gatewayRowsKeepSapsOwnServiceUrl() {
        def m = gateway("404", 200, V2_BODY, 200, V4_BODY)
        assert m.getProperty("CATALOG_SOURCE_USED") == "gateway"
        def rows = m.getProperty("CATALOG_ROWS")
        assert rows.size() == 5
        // V2 rows pass through as the catalogue gives them, ServiceType included for the views
        assert rows[0].ID == "ZAPI_BUSINESS_PARTNER_0001" && rows[0].ServiceType == "WEB_API"
        // a V4 row with a ServiceUrl keeps its path on the configured host
        assert rowOf(m, "API_COST_CENTER_SRV").ServiceUrl == HOST + "/sap/opu/odata4/sap/api_cost_center/srvd_a2x/sap/costcenter/0001"
        assert rowOf(m, "API_COST_CENTER_SRV").Description == "Cost Center"
        // a V4 row without one: the mapping by group, else a derived address, marked
        assert rowOf(m, "PRODUCT").ServiceUrl == HOST + "/sap/opu/odata4/sap/api_product/srvd_a2x/sap/product/0001"
        assert rowOf(m, "PRODUCT").Description == "Product"
        assert rowOf(m, "ZMY_SRV").ServiceUrl == HOST + "/sap/opu/odata4/sap/zmy_group/srvd_a2x/sap/zmy_srv/0001"
        assert rowOf(m, "ZMY_SRV").Description == "Mine (address derived, unverified)"
        assert m.getProperty("VERIFY_MORE") == "false"
    }

    @Test
    void theArrangementsWinWhenBothAreReadable() {
        def m = run(GW_RAN: "true", GW_V2_STATUS: "200", GW_V2_BODY: V2_BODY)
        assert m.getProperty("CATALOG_SOURCE_USED") == "arrangements"
    }

    // The wrong-host guard and the other refusals
    @Test
    void aWebFrontEndIsAConfigurationErrorBeforeAnyListing() {
        def e = Scripts.failure { gateway("404", 404, "<html>not found</html>", 404, "<html>") }
        assert e.message.startsWith("CONFIG: S4_HOSTNAME is not the API address")
        assert e.message.contains("HTTP 404")
        def redirect = Scripts.failure { gateway("302", 302, "", 301, "") }
        assert redirect.message.startsWith("CONFIG:")
        def html = Scripts.failure { gateway("NOT_JSON", 200, "<html>login</html>", 200, "<html>login</html>") }
        assert html.message.startsWith("CONFIG:")
        assert html.message.contains("NOT_JSON")
    }

    @Test
    void gatewayOnlyModeAgainstAWebFrontEndIsAConfigurationErrorToo() {
        def e = Scripts.failure { gateway(null, 404, "", 302, "", [CATALOG_SOURCE_MODE: "gateway"]) }
        assert e.message.startsWith("CONFIG: S4_HOSTNAME is not the API address")
    }

    @Test
    void cloudWithoutTheArrangementScenarioNamesIt() {
        def e = Scripts.failure { gateway("403", 403, '{"error":{}}', 403, '{"error":{}}') }
        assert e.message.startsWith("UPSTREAM:")
        assert e.message.contains("SAP_COM_0A07")
        assert e.message.contains("HTTP 403")
    }

    @Test
    void forcedArrangementsThatAreNotThereNameTheScenario() {
        def e = Scripts.failure { run(CATALOG_SOURCE_MODE: "arrangements", ARR_OUTCOME: "HTTP_404", ARR_FIRST_STATUS: "404") }
        assert e.message.startsWith("UPSTREAM: The communication arrangements (communication scenario SAP_COM_0A07)")
        assert e.message.contains("CATALOG_SOURCE")
    }

    @Test
    void aRefusedSignInIsAnUpstreamError() {
        def e = Scripts.failure { run(ARR_OUTCOME: "HTTP_401", ARR_FIRST_STATUS: "401") }
        assert e.message.startsWith("UPSTREAM: SAP refused the sign-in (HTTP 401)")
        def g = Scripts.failure { gateway("404", 401, "", 401, "") }
        assert g.message.contains("HTTP 401")
    }

    @Test
    void aServerErrorIsAnUpstreamError() {
        def e = Scripts.failure { gateway("404", 500, "", 503, "") }
        assert e.message.startsWith("UPSTREAM: SAP answered the catalogue requests with a server error")
    }

    @Test
    void anExhaustedDeadlineIsATimeout() {
        def e = Scripts.failure { run(ARR_OUTCOME: "DEADLINE", ARR_CALLS: "7") }
        assert e.message.contains("timed out")
        assert !e.message.startsWith("UPSTREAM:")
    }

    @Test
    void nothingAssignedIsAnEmptyListNotAnError() {
        def m = run(ARR_INBOUND: [inbound("a1", "DEBMAS_IDOC", "IDOC")])
        assert m.getProperty("CATALOG_ROWS") == []
        assert m.getProperty("CATALOG_SOURCE_USED") == "arrangements"
    }
}
