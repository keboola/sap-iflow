package com.keboola.cpi.tests.catalogue

import org.junit.Ignore
import org.junit.Test

// The place for the catalogue's tests. queryServices.groovy of 1.0.0 is being replaced
// (BUILD-BRIEF-v11 §4.2) and is not tested; the pure logic of its successor — the V2 address
// rule, the inbound-user filter, unlisted V4 names, the views, the sort order, the wrong-host
// guard — is tested here, loaded with Scripts.load(Scripts.CATALOGUE, "<script>").
class CatalogueScriptsTest {

    @Ignore("waiting for the catalogue build script of 1.1.0")
    @Test
    void placeholder() {
    }
}
