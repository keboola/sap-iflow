package com.keboola.cpi.tests.delivery

import com.keboola.cpi.tests.Scripts
import com.sap.it.api.msglog.MessageLogFactory
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message

// preparePage.groovy: the SAP request of one page — query options, delta window, next links.
class PreparePageTest {

    Script script
    MessageLogFactory logs

    @Before
    void load() {
        logs = new MessageLogFactory()
        script = Scripts.load(Scripts.DELIVERY, "preparePage", logs)
    }

    static Map firstPage() {
        return [KEBOOLA_MESSAGE_ID: "run-1",
                DLV_servicePath: "/sap/opu/odata/sap/API_BUSINESS_PARTNER",
                DLV_entitySet: "A_BusinessPartner",
                DLV_pageSize: "500",
                DLV_select: "BusinessPartner,LastChangeDate",
                DLV_primaryKey: "BusinessPartner",
                DLV_filter: "Country eq 'CZ'",
                CFG_SAP_CLIENT: "100"]
    }

    static String decodedFilter(String query) {
        String option = query.split("&").find { it.startsWith("%24filter=") }
        return java.net.URLDecoder.decode(option.substring("%24filter=".length()), "UTF-8")
    }

    // cfg, encode
    @Test
    void unsetAndPlaceholderValuesFallBack() {
        def m = message(properties: [A: "  ", B: "{{S4_HOSTNAME}}", C: " value "])
        assert script.cfg(m, "A", "fallback") == "fallback"
        assert script.cfg(m, "B", "fallback") == "fallback"
        assert script.cfg(m, "C", "fallback") == "value"
        assert script.cfg(m, "MISSING", "fallback") == "fallback"
    }

    @Test
    void encodesForAQueryString() {
        assert script.encode("Country eq 'CZ'") == "Country%20eq%20%27CZ%27"
        assert script.encode("A,B") == "A%2CB"
        assert script.encode("plain") == "plain"
    }

    // truncate, lowerBound, literal
    @Test
    void dayPrecisionTruncatesToMidnight() {
        assert script.truncate("2026-09-30T10:11:12", "day") == "2026-09-30T00:00:00"
        assert script.truncate("2026-09-30T10:11:12", "second") == "2026-09-30T10:11:12"
        assert script.truncate("2026", "day") == "2026"
        assert script.truncate("", "day") == ""
    }

    @Test
    void lowerBoundStartsADayEarlyAtDayPrecisionOnly() {
        assert script.lowerBound("2026-09-30T10:11:12", "day") == "2026-09-29T00:00:00"
        assert script.lowerBound("2026-03-01T00:00:00", "day") == "2026-02-28T00:00:00"
        assert script.lowerBound("2026-09-30T10:11:12", "second") == "2026-09-30T10:11:12"
        assert script.lowerBound("2026-09-30T10:11:12", "day", 15L) == "2026-09-29T00:00:00"
        assert script.lowerBound("", "day") == ""
    }

    @Test
    void literalsFollowTheODataVersionAndFieldType() {
        assert script.literal("2026-09-30T10:11:12", "datetime", false) == "datetime'2026-09-30T10:11:12'"
        assert script.literal("2026-09-30T10:11:12", "datetimeoffset", false) == "datetimeoffset'2026-09-30T10:11:12Z'"
        assert script.literal("2026-09-30T10:11:12", "date", false) == "datetime'2026-09-30T00:00:00'"
        assert script.literal("2026-09-30T10:11:12", "datetime", true) == "2026-09-30T10:11:12Z"
        assert script.literal("2026-09-30T10:11:12", "datetimeoffset", true) == "2026-09-30T10:11:12Z"
        assert script.literal("2026-09-30T10:11:12", "date", true) == "2026-09-30"
    }

    // At second precision the window starts DELTA_OVERLAP_MINUTES before the watermark
    @Test
    void lowerBoundOverlapsAtSecondPrecision() {
        assert script.lowerBound("2026-09-30T10:11:12", "second", 15L) == "2026-09-30T09:56:12"
        assert script.lowerBound("2026-10-01T00:10:00", "second", 15L) == "2026-09-30T23:55:00"
        assert script.lowerBound("2026-09-30T10:11:12", "second", 0L) == "2026-09-30T10:11:12"
        assert script.lowerBound("2026-09-30T10:11:12", "second", 10080L) == "2026-09-23T10:11:12"
        assert script.lowerBound("2026-09-30", "second", 15L) == "2026-09-30"
        assert script.lowerBound("not a time here", "second", 15L) == "not a time here"
    }

    @Test
    void deltaClauseAppliesTheOverlapAtSecondPrecisionOnly() {
        assert script.deltaClause("Changed", "2026-09-01T10:00:00", "2026-09-30T02:00:00", "datetimeoffset", "second", false, 15L) ==
            "Changed ge datetimeoffset'2026-09-01T09:45:00Z' and Changed le datetimeoffset'2026-09-30T02:00:00Z'"
        assert script.deltaClause("Changed", "2026-09-01T10:00:00", "2026-09-30T02:00:00", "datetime", "day", false, 15L) ==
            "Changed ge datetime'2026-08-31T00:00:00' and Changed le datetime'2026-09-30T00:00:00'"
        assert script.deltaClause("Changed", "", "2026-09-30T02:00:00", "datetimeoffset", "second", false, 15L) ==
            "Changed le datetimeoffset'2026-09-30T02:00:00Z'"
    }

    // deltaClause
    @Test
    void deltaClauseBoundsTheWindowOnBothSides() {
        def clause = script.deltaClause("LastChangeDate", "2026-09-01T10:00:00", "2026-09-30T02:00:00", "datetime", "day", false)
        assert clause == "LastChangeDate ge datetime'2026-08-31T00:00:00' and LastChangeDate le datetime'2026-09-30T00:00:00'"
    }

    @Test
    void deltaClauseAtSecondPrecisionUsesTheExactTimes() {
        def clause = script.deltaClause("LastChangeDate", "2026-09-01T10:00:00", "2026-09-30T02:00:00", "datetimeoffset", "second", true)
        assert clause == "LastChangeDate ge 2026-09-01T10:00:00Z and LastChangeDate le 2026-09-30T02:00:00Z"
    }

    @Test
    void deltaClauseWithoutWatermarkHasOnlyTheUpperBound() {
        assert script.deltaClause("Changed", "", "2026-09-30T02:00:00", "datetime", "day", false) == "Changed le datetime'2026-09-30T00:00:00'"
        assert script.deltaClause("Changed", null, "2026-09-30T02:00:00", "datetime", "day", false) == "Changed le datetime'2026-09-30T00:00:00'"
    }

    @Test
    void deltaClauseJoinsSeveralFieldsWithOr() {
        def clause = script.deltaClause("Created, Changed", "2026-09-01T00:00:00", "", "date", "day", false)
        assert clause == "(Created ge datetime'2026-08-31T00:00:00') or (Changed ge datetime'2026-08-31T00:00:00')"
    }

    @Test
    void deltaClauseIsEmptyWithoutFieldsOrBounds() {
        assert script.deltaClause("", "2026-09-01T00:00:00", "2026-09-30T00:00:00", "datetime", "day", false) == ""
        assert script.deltaClause("Changed", "", "", "datetime", "day", false) == ""
        assert script.deltaClause(null, "", "", "datetime", "day", false) == ""
    }

    // resolveLink
    @Test
    void absoluteNextLinkKeepsOnlyPathAndQuery() {
        def target = script.resolveLink('https://other.example.com:443/sap/opu/odata/sap/SVC/Set?$skiptoken=2&x=1', "/sap/opu/odata/sap/SVC")
        assert target.path == "/sap/opu/odata/sap/SVC/Set"
        assert target.query == '$skiptoken=2&x=1&%24format=json'
    }

    @Test
    void relativeNextLinkIsResolvedAgainstTheServicePath() {
        def target = script.resolveLink("Set?%24skiptoken=2", "/sap/opu/odata/sap/SVC")
        assert target.path == "/sap/opu/odata/sap/SVC/Set"
        assert target.query == "%24skiptoken=2&%24format=json"
        def rooted = script.resolveLink("/sap/opu/odata4/sap/svc/Set", "/ignored")
        assert rooted.path == "/sap/opu/odata4/sap/svc/Set"
        assert rooted.query == "%24format=json"
    }

    @Test
    void formatJsonIsAddedOnlyWhenMissing() {
        assert script.resolveLink('Set?$format=json&$skiptoken=1', "/svc").query == '$format=json&$skiptoken=1'
        assert script.resolveLink("Set?%24format=json", "/svc").query == "%24format=json"
        assert script.resolveLink("Set", "/svc").query == "%24format=json"
    }

    @Test
    void hostOnlyLinkResolvesToTheRoot() {
        assert script.resolveLink("https://host.example.com", "/svc").path == "/"
        assert script.resolveLink("HTTP://host.example.com/x", "/svc").path == "/x"
    }

    // withSapClient, afterHost
    @Test
    void sapClientIsAppendedOnceAndOnlyWhenConfigured() {
        assert script.withSapClient("%24top=10", "100") == "%24top=10&sap-client=100"
        assert script.withSapClient("", "100") == "sap-client=100"
        assert script.withSapClient("%24top=10", "") == "%24top=10"
        assert script.withSapClient("%24top=10", null) == "%24top=10"
        assert script.withSapClient("sap-client=200&%24top=10", "100") == "sap-client=200&%24top=10"
        assert script.withSapClient("%24top=10&SAP-CLIENT=200", "100") == "%24top=10&SAP-CLIENT=200"
        assert script.withSapClient("x=1", "1 0") == "x=1&sap-client=1%200"
    }

    @Test
    void leadingSlashDropsWhenTheHostAlreadyEndsWithOne() {
        assert script.afterHost("/sap/x", true) == "sap/x"
        assert script.afterHost("/sap/x", false) == "/sap/x"
        assert script.afterHost("sap/x", true) == "sap/x"
    }

    // processData: first page
    @Test
    void firstPageQueryHasEveryOption() {
        def m = message(properties: firstPage(),
                        headers: [CamelHttpPath: "/keboola/deliver", CamelHttpQuery: "a=b", CamelHttpUri: "u", CamelHttpUrl: "u"])
        script.processData(m)
        assert m.getProperty("PAGE_NUMBER") == "1"
        assert m.getProperty("PAGE_VERDICT") == "ok"
        assert m.getProperty("PAGE_KEY") == "run-1-1"
        assert m.getProperty("PAGE_ASKED_BY_LINK") == "false"
        assert m.getProperty("S4_TARGET_PATH") == "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
        assert m.getProperty("S4_QUERY_STRING") == "%24format=json&%24top=500&%24select=BusinessPartner%2CLastChangeDate" +
            "&%24orderby=BusinessPartner&%24filter=%28Country%20eq%20%27CZ%27%29&sap-client=100"
        ["CamelHttpPath", "CamelHttpQuery", "CamelHttpUri", "CamelHttpUrl"].each { assert m.getHeaders()[it] == null }
        assert logs.logOf(m).customHeaderProperties["FirstPageQuery"] ==
            '$format=json&$top=500&$select=BusinessPartner,LastChangeDate&$orderby=BusinessPartner&$filter=(Country eq \'CZ\')'
    }

    @Test
    void laterPagesSkipAndStaySilent() {
        def m = message(properties: firstPage() + [PAGE_NUMBER: "1", NEXT_SKIP: "500"])
        script.processData(m)
        assert m.getProperty("PAGE_NUMBER") == "2"
        assert m.getProperty("PAGE_KEY") == "run-1-2"
        assert m.getProperty("S4_QUERY_STRING").contains("&%24skip=500&")
        assert logs.logOf(m).customHeaderProperties["FirstPageQuery"] == null
    }

    @Test
    void bareRequestHasOnlyFormatAndTop() {
        def m = message(properties: [DLV_servicePath: "/svc", DLV_entitySet: "Set"])
        script.processData(m)
        assert m.getProperty("S4_TARGET_PATH") == "/svc/Set"
        assert m.getProperty("S4_QUERY_STRING") == "%24format=json&%24top=1000"
        assert m.getProperty("PAGE_KEY") == "unknown-1"
    }

    @Test
    void integrationKeyHeaderNeedsBothParts() {
        def m = message(properties: firstPage() + [CFG_AIR_KEY: "key-1", CFG_AIR_HEADER_NAME: "X-SAP-AIR"])
        script.processData(m)
        assert m.getHeaders()["X-SAP-AIR"] == "key-1"
        def half = message(properties: firstPage() + [CFG_AIR_KEY: "key-1", CFG_AIR_HEADER_NAME: "{{AIR_HEADER_NAME}}"])
        script.processData(half)
        assert half.getHeaders().keySet().every { !it.contains("AIR") }
    }

    @Test
    void hostWithTrailingSlashGetsARelativeTargetPath() {
        def m = message(properties: firstPage() + [CFG_S4_HOSTNAME: "https://s4.example.com/"])
        script.processData(m)
        assert m.getProperty("S4_TARGET_PATH") == "sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
    }

    // processData: incremental
    @Test
    void incrementalFirstPageFiltersTheWindowAndLogsIt() {
        def m = message(properties: firstPage() + [DLV_loadMode: "incremental", DLV_deltaField: "LastChangeDate",
            DLV_watermark: "2026-09-01T10:00:00", DLV_runStartedAt: "2026-09-30T02:00:00",
            DLV_deltaFieldType: "datetime", DLV_deltaPrecision: "day"])
        script.processData(m)
        def window = "LastChangeDate ge datetime'2026-08-31T00:00:00' and LastChangeDate le datetime'2026-09-30T00:00:00'"
        assert logs.logOf(m).customHeaderProperties["DeltaWindow"] == window
        assert decodedFilter(m.getProperty("S4_QUERY_STRING")) == "(Country eq 'CZ') and (" + window + ")"
    }

    @Test
    void incrementalWithoutOwnFilterHasJustTheWindow() {
        def m = message(properties: firstPage() + [DLV_filter: "", DLV_loadMode: "incremental", DLV_deltaField: "LastChangeDate",
            DLV_watermark: "2026-09-01T10:00:00", DLV_runStartedAt: "2026-09-30T02:00:00"])
        script.processData(m)
        assert decodedFilter(m.getProperty("S4_QUERY_STRING")) ==
            "(LastChangeDate ge datetime'2026-08-31T00:00:00' and LastChangeDate le datetime'2026-09-30T00:00:00')"
    }

    @Test
    void incrementalV4UsesPlainLiterals() {
        def m = message(properties: firstPage() + [DLV_servicePath: "/sap/opu/odata4/sap/api_business_partner/srvd_a2x/sap/businesspartner/0001",
            DLV_loadMode: "incremental", DLV_deltaField: "LastChangeDateTime",
            DLV_watermark: "2026-09-01T10:00:00", DLV_runStartedAt: "2026-09-30T02:00:00",
            DLV_deltaFieldType: "datetimeoffset", DLV_deltaPrecision: "second"])
        script.processData(m)
        assert logs.logOf(m).customHeaderProperties["DeltaWindow"] ==
            "LastChangeDateTime ge 2026-09-01T10:00:00Z and LastChangeDateTime le 2026-09-30T02:00:00Z"
    }

    @Test
    void incrementalAtSecondPrecisionOverlapsAndLogsIt() {
        def m = message(properties: firstPage() + [DLV_filter: "", DLV_loadMode: "incremental", DLV_deltaField: "LastChangeDateTime",
            DLV_watermark: "2026-09-01T10:00:00", DLV_runStartedAt: "2026-09-30T02:00:00",
            DLV_deltaFieldType: "datetimeoffset", DLV_deltaPrecision: "second", DLV_deltaOverlapMinutes: "15"])
        script.processData(m)
        def window = "LastChangeDateTime ge datetimeoffset'2026-09-01T09:45:00Z' and LastChangeDateTime le datetimeoffset'2026-09-30T02:00:00Z'"
        assert logs.logOf(m).customHeaderProperties["DeltaWindow"] == window
        assert logs.logOf(m).customHeaderProperties["DeltaOverlap"] == "15 min before 2026-09-01T10:00:00"
        assert decodedFilter(m.getProperty("S4_QUERY_STRING")) == "(" + window + ")"
        def daily = message(properties: firstPage() + [DLV_loadMode: "incremental", DLV_deltaField: "LastChangeDate",
            DLV_watermark: "2026-09-01T10:00:00", DLV_runStartedAt: "2026-09-30T02:00:00", DLV_deltaOverlapMinutes: "15"])
        script.processData(daily)
        assert logs.logOf(daily).customHeaderProperties["DeltaOverlap"] == null
        def first = message(properties: firstPage() + [DLV_loadMode: "incremental", DLV_deltaField: "LastChangeDateTime",
            DLV_watermark: "", DLV_runStartedAt: "2026-09-30T02:00:00", DLV_deltaFieldType: "datetimeoffset",
            DLV_deltaPrecision: "second", DLV_deltaOverlapMinutes: "15"])
        script.processData(first)
        assert logs.logOf(first).customHeaderProperties["DeltaOverlap"] == null
        assert logs.logOf(first).customHeaderProperties["DeltaWindow"] == "LastChangeDateTime le datetimeoffset'2026-09-30T02:00:00Z'"
    }

    @Test
    void deltaWindowIsLoggedOnTheFirstPageOnly() {
        def m = message(properties: firstPage() + [PAGE_NUMBER: "1", DLV_loadMode: "incremental", DLV_deltaField: "LastChangeDate",
            DLV_watermark: "2026-09-01T10:00:00", DLV_runStartedAt: "2026-09-30T02:00:00"])
        script.processData(m)
        assert logs.logOf(m).customHeaderProperties["DeltaWindow"] == null
        assert m.getProperty("S4_QUERY_STRING").contains("%24filter=")
    }

    @Test
    void fullLoadHasNoWindow() {
        def m = message(properties: firstPage() + [DLV_loadMode: "full", DLV_deltaField: "LastChangeDate",
            DLV_watermark: "2026-09-01T10:00:00", DLV_runStartedAt: "2026-09-30T02:00:00"])
        script.processData(m)
        assert decodedFilter(m.getProperty("S4_QUERY_STRING")) == "(Country eq 'CZ')"
        assert logs.logOf(m).customHeaderProperties["DeltaWindow"] == null
    }

    // processData: next link
    @Test
    void nextLinkReplacesTheQueryOptions() {
        def m = message(properties: firstPage() + [PAGE_NUMBER: "1",
            NEXT_LINK: 'https://s4.example.com/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner?$skiptoken=500'])
        script.processData(m)
        assert m.getProperty("PAGE_NUMBER") == "2"
        assert m.getProperty("PAGE_ASKED_BY_LINK") == "true"
        assert m.getProperty("S4_TARGET_PATH") == "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
        assert m.getProperty("S4_QUERY_STRING") == '$skiptoken=500&%24format=json&sap-client=100'
        assert logs.logOf(m).customHeaderProperties["FirstPageQuery"] == null
    }

    @Test
    void relativeNextLinkGetsTheServicePath() {
        def m = message(properties: firstPage() + [NEXT_LINK: "A_BusinessPartner?%24skiptoken=500", CFG_SAP_CLIENT: ""])
        script.processData(m)
        assert m.getProperty("S4_TARGET_PATH") == "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner"
        assert m.getProperty("S4_QUERY_STRING") == "%24skiptoken=500&%24format=json"
    }
}
