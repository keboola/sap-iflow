package com.keboola.cpi.tests.delivery

import com.keboola.cpi.tests.Scripts
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import org.junit.Before
import org.junit.Test

import static com.keboola.cpi.tests.Scripts.message
import static org.junit.Assume.assumeTrue

// odataToCsv.groovy: an OData JSON page becomes CSV; page identity and repeat detection.
class OdataToCsvTest {

    static final String V2_PAGE = '{"d":{"results":[' +
        '{"__metadata":{"uri":"u1"},"Id":"1","Name":"a,b"},' +
        '{"__metadata":{"uri":"u2"},"Id":"2","Name":"x"}' +
        '],"__next":"https://host/svc/Set?$skiptoken=2"}}'

    Script script

    @Before
    void load() {
        script = Scripts.load(Scripts.DELIVERY, "odataToCsv")
    }

    static Message page(Map properties, String body) {
        return message(properties: [PAGE_NUMBER: "1"] + properties, body: body)
    }

    // csvEscape
    @Test
    void escapesOnlyWhatCsvNeeds() {
        assert script.csvEscape("plain") == "plain"
        assert script.csvEscape("a,b") == '"a,b"'
        assert script.csvEscape('say "hi"') == '"say ""hi"""'
        assert script.csvEscape("two\nlines") == '"two\nlines"'
        assert script.csvEscape("cr\rhere") == '"cr\rhere"'
        assert script.csvEscape(null) == ""
        assert script.csvEscape("") == ""
    }

    // normaliseDate — 1.0.0 behaviour: UTC with a Z suffix, the offset of /Date(ms+0100)/ ignored
    @Test
    void edmDateTimeBecomesIsoUtcWithZ() {
        assert script.normaliseDate("/Date(1696032000000)/") == "2023-09-30T00:00:00Z"
        assert script.normaliseDate("/Date(1696032000000+0100)/") == "2023-09-30T00:00:00Z"
        assert script.normaliseDate("/Date(-86400000)/") == "1969-12-31T00:00:00Z"
        assert script.normaliseDate("/Date(1696032000000)") == "/Date(1696032000000)"
        assert script.normaliseDate("2023-09-30T00:00:00") == "2023-09-30T00:00:00"
        assert script.normaliseDate("/Date(abc)/") == "/Date(abc)/"
    }

    // normaliseTime — 1.0.0 behaviour: whole seconds only, fractions pass through unchanged
    @Test
    void edmTimeBecomesClockTime() {
        assert script.normaliseTime("PT10H20M30S") == "10:20:30"
        assert script.normaliseTime("PT1H2M3S") == "01:02:03"
        assert script.normaliseTime("PT0H0M0S") == "00:00:00"
        assert script.normaliseTime("PT1H2M3.5S") == "PT1H2M3.5S"
        assert script.normaliseTime("PT10H") == "PT10H"
        assert script.normaliseTime("10:20:30") == "10:20:30"
    }

    // render
    @Test
    void rendersScalarsAsText() {
        assert script.render(null) == ""
        assert script.render(new BigDecimal("1E+3")) == "1000"
        assert script.render(new BigDecimal("12.50")) == "12.50"
        assert script.render(42) == "42"
        assert script.render(true) == "true"
        assert script.render("/Date(1696032000000)/") == "2023-09-30T00:00:00Z"
        assert script.render("PT1H2M3S") == "01:02:03"
    }

    // flatten
    @Test
    void flattensNestedObjectsAndSkipsMetadataLinksAndCollections() {
        def target = [:]
        script.flatten([
            __metadata: [uri: "u1"],
            Id: "1",
            Address: [City: "Prague", Geo: [Lat: 50]],
            "Name@odata.type": "#String",
            Supplier: [__deferred: [uri: "Supplier"]],
            Items: [[No: 1]],
            Note: null
        ], "", target)
        assert target == [Id: "1", Address_City: "Prague", Address_Geo_Lat: "50", Note: ""]
    }

    @Test
    void flattenKeepsThePrefixItWasGiven() {
        def target = [:]
        script.flatten([A: [B: "x"]], "P_", target)
        assert target == [P_A_B: "x"]
    }

    // identityOf, pageIdentity
    @Test
    void rowIdentityComesFromTheUriThenTheOdataIdThenTheKeys() {
        assert script.identityOf([__metadata: [uri: "u1"], Id: "1"]) == "u1"
        assert script.identityOf(["@odata.id": "o1", Id: "1"]) == "o1"
        assert script.identityOf([__metadata: [uri: "u1"], Id: "1"], ["Id"]) == "u1"
        assert script.identityOf([Id: "1", Ver: "A"], ["Id", "Ver"]) == "key:1:1,1:A"
        assert script.identityOf([Id: "10", Ver: "AB"], ["Id", "Ver"]) == "key:2:10,2:AB"
        assert script.identityOf([Id: "1"], ["Id", "Ver"]) == ""
        assert script.identityOf([Id: "1"]) == ""
        assert script.identityOf("not a row") == ""
    }

    @Test
    void pageIdentityIsFirstLastAndCount() {
        def rows = [[__metadata: [uri: "u1"]], [__metadata: [uri: "u2"]], [__metadata: [uri: "u3"]]]
        assert script.pageIdentity(rows) == "u1|u3|3"
        assert script.pageIdentity([[Id: "1"], [Id: "2"]], ["Id"]) == "key:1:1|key:1:2|2"
        assert script.pageIdentity([[__metadata: [uri: "u1"]], [Id: "2"]]) == ""
        assert script.pageIdentity([]) == ""
        assert script.pageIdentity(null) == ""
    }

    // processData: pages
    @Test
    void v2PageBecomesCsvWithPagingState() {
        def m = page([DLV_pageSize: "2", DLV_select: "Id,Name"], V2_PAGE)
        script.processData(m)
        assert m.getBody() == 'Id,Name\n1,"a,b"\n2,x\n'
        assert m.getHeaders()["Content-Type"] == "text/csv"
        assert m.getProperty("ROWS_IN_PAGE") == "2"
        assert m.getProperty("NEXT_LINK") == 'https://host/svc/Set?$skiptoken=2'
        assert m.getProperty("NEXT_SKIP") == "2"
        assert m.getProperty("WINDOW_ROWS") == "2"
        assert m.getProperty("MORE_PAGES") == "true"
        assert m.getProperty("FIRST_DATA_PAGE") == "1"
        assert m.getProperty("SKIP_PAGE_IDENTITY") == "u1|u2|2"
        assert m.getProperty("CSV_COLUMNS") == "Id,Name"
        assert m.getProperty("RUN_FAILED") == null
    }

    @Test
    void v4PageIsReadFromValueAndNextLink() {
        def m = page([DLV_select: "Id"], '{"value":[{"@odata.id":"o1","Id":"1"}],"@odata.nextLink":"Set?$skiptoken=1"}')
        script.processData(m)
        assert m.getBody() == "Id\n1\n"
        assert m.getProperty("NEXT_LINK") == 'Set?$skiptoken=1'
        assert m.getProperty("MORE_PAGES") == "true"
        assert m.getProperty("SKIP_PAGE_IDENTITY") == "o1|o1|1"
    }

    @Test
    void singleEntityAnswerIsOneRow() {
        def m = page([DLV_select: "Id,Name"], '{"d":{"Id":"1","Name":"only"}}')
        script.processData(m)
        assert m.getBody() == "Id,Name\n1,only\n"
        assert m.getProperty("ROWS_IN_PAGE") == "1"
        assert m.getProperty("NEXT_LINK") == ""
        assert m.getProperty("MORE_PAGES") == "false"
    }

    @Test
    void nextSkipAccumulates() {
        def m = page([DLV_pageSize: "2", DLV_select: "Id", NEXT_SKIP: "4"], V2_PAGE)
        script.processData(m)
        assert m.getProperty("NEXT_SKIP") == "6"
    }

    // processData: answers that are not a collection end the run
    @Test
    void jsonWithoutCollectionEndsTheRun() {
        def m = page([:], '{"error":{"message":{"value":"no such set"}}}')
        script.processData(m)
        assert m.getProperty("RUN_FAILED") == "UPSTREAM_FAILED"
        assert m.getProperty("RUN_FAILED_DETAIL").contains("page 1")
        assert m.getProperty("RUN_FAILED_DETAIL").contains("not an OData collection")
        assert m.getProperty("RUN_FAILED_DETAIL").contains("no such set")
        assert m.getProperty("MORE_PAGES") == "false"
        assert m.getProperty("ROWS_IN_PAGE") == "0"
        assert m.getProperty("NEXT_LINK") == ""
        assert m.getBody() == ""
    }

    @Test
    void missingOrBlankBodyEndsTheRun() {
        [null, "", "   \n"].each { body ->
            def m = page([:], body)
            script.processData(m)
            assert m.getProperty("RUN_FAILED") == "UPSTREAM_FAILED"
            assert m.getProperty("RUN_FAILED_DETAIL").contains("without a body")
            assert m.getBody() == ""
        }
    }

    // Column order
    @Test
    void odataSelectFixesTheColumnOrder() {
        def m = page([DLV_select: "Name, Id ,Missing"], V2_PAGE)
        script.processData(m)
        assert m.getBody().readLines()[0] == "Name,Id,Missing"
        assert m.getBody().readLines()[1] == '"a,b",1,'
        assert m.getProperty("CSV_COLUMNS") == "Name,Id,Missing"
    }

    @Test
    void rememberedColumnsWinOverTheRowShape() {
        def m = page([CSV_COLUMNS: "Id", DLV_select: "Id,Name"], V2_PAGE)
        script.processData(m)
        assert m.getBody() == "Id\n1\n2\n"
    }

    @Test
    void withoutSelectEveryFlattenedFieldOfTheFirstRowIsAColumn() {
        def m = page([:], '{"d":{"results":[{"__metadata":{"uri":"u1"},"Id":"1","Address":{"City":"Prague"}},{"Id":"2","Extra":"ignored"}]}}')
        script.processData(m)
        def lines = m.getBody().readLines()
        assert (lines[0].split(",") as Set) == (["Id", "Address_City"] as Set)
        assert lines[2].split(",", -1).size() == 2
        assert !m.getBody().contains("ignored")
    }

    @Test
    void columnsFollowTheFieldOrderOfTheFirstRow() {
        // Groovy 2.4's JsonSlurper keeps the field order only on Java 8, the runtime's Java
        // (F11-R2); on a newer JDK it sorts the names, so the check runs on Java 8 only.
        assumeTrue("needs Java 8, see F11-R2", keysStayInOrder())
        def m = page([:], '{"d":{"results":[{"Z":"1","A":"2","M":"3"}]}}')
        script.processData(m)
        assert m.getBody() == "Z,A,M\n1,2,3\n"
    }

    static boolean keysStayInOrder() {
        return new JsonSlurper().parseText('{"b":1,"a":2}').keySet().toList() == ["b", "a"]
    }

    // MORE_PAGES
    @Test
    void fullPageWithoutNextLinkMeansMorePages() {
        def m = page([DLV_pageSize: "2", DLV_select: "Id"], '{"d":{"results":[{"Id":"1"},{"Id":"2"}]}}')
        script.processData(m)
        assert m.getProperty("MORE_PAGES") == "true"
    }

    @Test
    void shortPageWithoutNextLinkIsTheLast() {
        def m = page([DLV_pageSize: "3", DLV_select: "Id"], '{"d":{"results":[{"Id":"1"},{"Id":"2"}]}}')
        script.processData(m)
        assert m.getProperty("MORE_PAGES") == "false"
        assert m.getProperty("WINDOW_ROWS") == "2"
    }

    @Test
    void pagesAskedByLinkCountTowardsTheRequestWindow() {
        def m = page([DLV_pageSize: "3", DLV_select: "Id", PAGE_ASKED_BY_LINK: "true", WINDOW_ROWS: "2"],
                     '{"d":{"results":[{"Id":"3"}]}}')
        script.processData(m)
        assert m.getProperty("WINDOW_ROWS") == "3"
        assert m.getProperty("MORE_PAGES") == "true"
        def partial = page([DLV_pageSize: "3", DLV_select: "Id", PAGE_ASKED_BY_LINK: "true", WINDOW_ROWS: "0"],
                           '{"d":{"results":[{"Id":"3"}]}}')
        script.processData(partial)
        assert partial.getProperty("WINDOW_ROWS") == "1"
        assert partial.getProperty("MORE_PAGES") == "false"
    }

    @Test
    void emptyPageIsTheLastAndKeepsTheHeader() {
        def m = page([DLV_pageSize: "2", CSV_COLUMNS: "Id"], '{"d":{"results":[]}}')
        script.processData(m)
        assert m.getBody() == "Id\n"
        assert m.getProperty("ROWS_IN_PAGE") == "0"
        assert m.getProperty("MORE_PAGES") == "false"
        assert m.getProperty("FIRST_DATA_PAGE") == null
    }

    @Test
    void unreadablePageSizeFallsBackToOneThousand() {
        def rows = (1..1000).collect { '{"Id":"' + it + '"}' }.join(",")
        def m = page([DLV_pageSize: "many", DLV_select: "Id"], '{"d":{"results":[' + rows + ']}}')
        script.processData(m)
        assert m.getProperty("ROWS_IN_PAGE") == "1000"
        assert m.getProperty("MORE_PAGES") == "true"
    }

    // Repeat detection
    @Test
    void samePageTwiceMeansSkipIsIgnored() {
        def m = page([DLV_pageSize: "2", DLV_select: "Id", SKIP_PAGE_IDENTITY: "u1|u2|2", WINDOW_ROWS: "2"], V2_PAGE)
        script.processData(m)
        assert m.getProperty("RUN_FAILED") == "UPSTREAM_FAILED"
        assert m.getProperty("RUN_FAILED_DETAIL").contains('does not apply $skip')
        assert !m.getProperty("RUN_FAILED_DETAIL").contains("PRIMARY_KEY")
        assert m.getBody() == ""
    }

    @Test
    void samePageTwiceByKeyBlamesThePrimaryKey() {
        def body = '{"d":{"results":[{"Id":"1"},{"Id":"2"}]}}'
        def m = page([DLV_pageSize: "2", DLV_select: "Id", DLV_primaryKey: "Id", SKIP_PAGE_IDENTITY: "key:1:1|key:1:2|2"], body)
        script.processData(m)
        assert m.getProperty("RUN_FAILED") == "UPSTREAM_FAILED"
        assert m.getProperty("RUN_FAILED_DETAIL").contains("PRIMARY_KEY")
    }

    @Test
    void firstPageAgainAfterAFullWindowEndsTheRunQuietly() {
        def m = page([DLV_pageSize: "2", DLV_select: "Id", SKIP_PAGE_IDENTITY: "u1|u2|2", WINDOW_ROWS: "5"], V2_PAGE)
        script.processData(m)
        assert m.getProperty("RUN_FAILED") == null
        assert m.getProperty("PAGE_NOTE").contains("already handed out every record")
        assert m.getProperty("ROWS_IN_PAGE") == "0"
        assert m.getProperty("MORE_PAGES") == "false"
        assert m.getProperty("NEXT_LINK") == ""
        assert m.getBody() == ""
    }

    @Test
    void pagesAskedByLinkAreNeverCheckedForRepeats() {
        def m = page([DLV_pageSize: "2", DLV_select: "Id", SKIP_PAGE_IDENTITY: "u1|u2|2", PAGE_ASKED_BY_LINK: "true"], V2_PAGE)
        script.processData(m)
        assert m.getProperty("RUN_FAILED") == null
        assert m.getProperty("ROWS_IN_PAGE") == "2"
    }

    @Test
    void rowsWithoutIdentityAreNeverCalledRepeats() {
        def m = page([DLV_select: "Id", SKIP_PAGE_IDENTITY: ""], '{"d":{"results":[{"Id":"1"}]}}')
        script.processData(m)
        assert m.getProperty("SKIP_PAGE_IDENTITY") == ""
        assert m.getProperty("RUN_FAILED") == null
        assert m.getProperty("ROWS_IN_PAGE") == "1"
    }

    @Test
    void differentPagesAreNotRepeats() {
        def m = page([DLV_pageSize: "2", DLV_select: "Id", SKIP_PAGE_IDENTITY: "u0|u0|2"], V2_PAGE)
        script.processData(m)
        assert m.getProperty("RUN_FAILED") == null
        assert m.getProperty("SKIP_PAGE_IDENTITY") == "u1|u2|2"
    }

    // First data page
    @Test
    void firstDataPageIsRememberedOnce() {
        def m = page([PAGE_NUMBER: "3", FIRST_DATA_PAGE: "2", DLV_select: "Id"], V2_PAGE)
        script.processData(m)
        assert m.getProperty("FIRST_DATA_PAGE") == "2"
        def later = page([PAGE_NUMBER: "3", DLV_select: "Id"], V2_PAGE)
        script.processData(later)
        assert later.getProperty("FIRST_DATA_PAGE") == "3"
    }
}
