# Integration flow script tests

Unit tests for the Groovy scripts of the integration flows. Each test loads the real script
file with `GroovyShell` and a `Binding` that holds `messageLogFactory`, as SAP Cloud
Integration does, and drives it through a stub `Message`. Groovy 2.4.21 is the runtime's
Groovy; JUnit 4 runs the tests.

## Running

```sh
mvn test
```

The scripts are read from `${scripts.root}/<ArtifactId>/src/main/resources/script/`.
`scripts.root` defaults to the parent folder of this module, which holds the flow folders in
this repository. From another layout, name the folder:

```sh
mvn test -Dscripts.root=../artifacts
```

Any JDK from 8 on runs the tests. Field order in parsed JSON is kept only on Java 8 (the
runtime's Java); on a newer JDK Groovy 2.4's `JsonSlurper` sorts the field names, and the one
test that depends on the order is skipped with a note.

## Layout

```
src/main/java/com/sap/...              stubs of the SAP runtime classes the scripts import
src/test/groovy/com/keboola/cpi/tests/
  Scripts.groovy                       loads a script, builds a Message, catches a failure
  connector/                           KeboolaODataConnector
  catalogue/                           QueryAvailableServicesFromSAPS4HANA
  delivery/                            DeliverBusinessDataFromSAPS4HANAToKeboola
```

The stubs hold the methods the scripts call: `Message` (headers, properties, a body with the
runtime's conversions), `MessageLog` and `MessageLogFactory` (what a script writes to the
monitor, per message), `Factory` and `ITApiFactory` (answer the services a test registers,
else `null`), `DataStoreService`, `DataBean`, `DataConfig`, `SecureStoreService`,
`UserCredential`, `ValueMappingApi`.

## Adding a test for a script

1. Create `src/test/groovy/com/keboola/cpi/tests/<flow>/<ScriptName>Test.groovy`. A file named
   `*Test.groovy` is picked up without any registration.
2. Load the script once per test:

   ```groovy
   @Before
   void load() {
       logs = new MessageLogFactory()
       script = Scripts.load(Scripts.DELIVERY, "commitWatermark", logs)
   }
   ```

3. Call the script's `static` helpers directly, `script.deltaClause(...)`, and its entry point
   through a message: `script.processData(message(properties: [...], headers: [...], body: "..."))`.
4. Read what it wrote: `m.getProperty(...)`, `m.getHeaders()[...]`, `m.getBody()`, and
   `logs.logOf(m).customHeaderProperties`, `.properties`, `.attachments`.
5. A script that needs a runtime service gets one from the test:
   `Factory.register(DataStoreService, [get: { store, id -> bean }] as DataStoreService)`,
   `ITApiFactory.register(ValueMappingApi, [getMappedValue: { a, b, c, d, e -> "/path" }] as ValueMappingApi)`;
   call `Factory.reset()` / `ITApiFactory.reset()` in `@After`.
6. An expected exception: `def error = Scripts.failure { script.processData(m) }`.

Groovy `assert` gives the failure message; keep to Groovy 2.4 syntax (no lambdas, no `!in`).

## What is covered

198 tests in 15 classes.

| Flow | Script | Test class | Tests | Scenarios |
|---|---|---|---|---|
| Connector | `logIncoming` | `LogIncomingTest` | 12 | method, host and path-scope checks (`normalizePath`, `pathAllowed`, `CONNECTOR_PATH_PREFIXES`), masked header summary, monitor fields |
| Connector | `prepareS4Request` | `PrepareS4RequestTest` | 6 | rejected requests, target path and method, escaped characters, `sap-client`, the integration key header |
| Connector | `handleException` | `HandleExceptionTest` | 15 | error code mapping incl. `PATH_NOT_ALLOWED` and `CONNECTOR_ERROR`, the sign-in fault by text or by the throwing place, message cleaning and JSON escaping, monitor fields, the integration key header cleared |
| Catalogue | `planCatalogRead` | `PlanCatalogReadTest` | 10 | every start check, the address options, the diagnostics gate, the plan |
| Catalogue | `nextArrangementPage`, `recordArrangementPage` | `ArrangementLoopTest` | 8 | the arrangement API's pages, next links pinned to the host, the three entity sets, the outcomes, the page limit and the deadline |
| Catalogue | `buildCatalog` | `BuildCatalogTest` | 22 | the V2 address rule, the Value Mapping lookups, the four naming rules, the user filter, the resolve modes, Gateway rows, the wrong-host guard and the other refusals |
| Catalogue | `nextCandidate`, `recordCandidate` | `VerifyLoopTest` | 5 | one service-document call per candidate, the first 200 lists the service, the limits |
| Catalogue | `answerCatalog` | `AnswerCatalogTest` | 8 | the views, the sort order, verified guesses, unlisted names, response headers, diagnostics gate, monitor fields |
| Catalogue | `handleCatalogException` | `HandleCatalogExceptionTest` | 7 | the build script's `CONFIG:`/`UPSTREAM:` prefixes, unreachable host and timeout told apart, 405 with `Allow`, the sign-in fault by text or by the throwing place, `CATALOG_ERROR` with `ErrorLocation`, monitor fields |
| Delivery | `startDelivery` | `StartDeliveryTest` | 20 | every start check incl. page size (1…20000) and the overlap, the envelope, the watermark read and its failure flags, unsorted paging |
| Delivery | `startRun` | `StartRunTest` | 4 | the envelope becomes properties, the run state, the watermark note and the paging flag on the worker, an unreadable envelope |
| Delivery | `preparePage` | `PreparePageTest` | 31 | query encoding, date literals per OData version, `deltaClause` with the overlap, `resolveLink`, `withSapClient`, the first-page query, later pages and next links, the delta window |
| Delivery | `inspectPage` | `InspectPageTest` | 10 | status classes: ok, wrong host, final failure, retry; the error excerpt |
| Delivery | `odataToCsv` | `OdataToCsvTest` | 32 | flatten, `/Date()/` and `PT..S` values, CSV escaping, column order and the 64-character limit, answers that are not a collection, page identity, repeat detection, paging state |
| Delivery | `commitWatermark` | `CommitWatermarkTest` | 8 | the outcome for the Router (DELIVERED / FAILED), the watermark write and its failure status, the page ceiling, the error body |
