# SAP S/4HANA Integration with Keboola

[![build](https://github.com/keboola/sap-iflow/actions/workflows/build.yml/badge.svg)](https://github.com/keboola/sap-iflow/actions/workflows/build.yml)

SAP Integration Suite content that connects SAP S/4HANA to the Keboola data platform,
published on the SAP Business Accelerator Hub as the integration package
*SAP S/4HANA Integration with Keboola*, version 1.1.0.

Setup, configuration, authentication and troubleshooting are covered in the
[Integration Guide](docs/SAP-S4HANA-Integration-with-Keboola-Integration-Guide.pdf).

## Artifacts

| Artifact | ID | Version | Required | Purpose |
|---|---|---|---|---|
| Query Available Services from SAP S4HANA | `QueryAvailableServicesFromSAPS4HANA` | 1.1.0 | optional | Lists the OData services the SAP communication user can reach, read through the flow's own connections |
| SAP S4HANA Service Addresses for Keboola (value mapping) | `KeboolaServiceAddresses` | 1.0.0 | optional, with the catalog flow on SAP S/4HANA Cloud | Address book of OData V4 service paths and titles; rows are added in the value mapping editor, no flow is edited |
| Query Business Data from SAP S4HANA to Keboola | `KeboolaODataConnector` | 1.1.0 | required for the extractor | Read-only OData endpoint for Keboola's SAP extractor |
| Deliver Business Data from SAP S4HANA to Keboola | `DeliverBusinessDataFromSAPS4HANAToKeboola` | 1.1.0 | optional | Reads S/4HANA on a schedule or on request and writes into a Keboola Storage table |
| Keboola adapter | `Keboola` | 1.0.0 | optional | Receiver adapter that writes CSV into a Keboola Storage table |

## Endpoints

| Endpoint | Flow | Methods |
|---|---|---|
| `/http/keboola/catalog` | Query Available Services | `GET`, `HEAD` |
| `/http/keboola/connector/<OData path>` | Query Business Data | `GET`, `HEAD` |
| `/http/keboola/deliver` as shipped, set by `DELIVERY_ADDRESS` | Deliver Business Data (manual trigger) | `POST` |

A caller needs the user role named in the flow's `SENDER_ROLE` (`ESBMessaging.send` as
shipped). The connector forwards only paths under `CONNECTOR_PATH_PREFIXES` (the OData V2 and
V4 roots as shipped).

## Layout

```
integration-flows/       integration flow and value mapping sources, one folder per artifact
integration-flows/tests/ unit tests for the flows' Groovy scripts (Maven)
keboola-adapter/         receiver adapter source (Maven, SAP Adapter Development Kit)
dist/                    importable builds of the flows, the value mapping and the adapter
docs/                    Integration Guide (PDF)
.github/                 GitHub Actions build and Dependabot settings
```

## Requirements

- SAP Integration Suite with Cloud Integration; the delivery flow also needs JMS message queues
- SAP S/4HANA Cloud or on-premise, with a communication user and arrangements for the APIs to read
- A Keboola project; for the delivery flow, a Storage token with write access to the target bucket

## Installation

1. Copy the package from the SAP Business Accelerator Hub, or create a package and add the
   three flow zips from `dist/` as integration flows, `dist/KeboolaServiceAddresses.zip` as a
   value mapping and `dist/keboola-adapter-1.0.0.esa` as an integration adapter.
2. Deploy the security material: `KEBOOLA_S4` for SAP S/4HANA, of the type the sign-in method
   needs, and, for the delivery flow and the adapter, `KEBOOLA_STORAGE` holding the Keboola
   Storage token.
3. Set the Configure values of each flow: the SAP host, the sign-in method (`S4_AUTH_METHOD`)
   with its credential name, the sender role, and for the delivery flow its address, schedule
   and target table. Every system-specific value ships empty; no flow needs the editor.
4. Deploy.

## Building from source

Integration flows:

```sh
cd integration-flows/<ArtifactId>
zip -r -X ../../dist/<ArtifactId>.zip . -x '.DS_Store' '*/.DS_Store'
```

Value mapping:

```sh
cd integration-flows/KeboolaServiceAddresses
zip -X ../../dist/KeboolaServiceAddresses.zip META-INF/MANIFEST.MF value_mapping.xml .project
```

Adapter (JDK 8 or later, Maven 3):

```sh
cd keboola-adapter
mvn clean install
cp target/build/keboola-adapter.esa ../dist/keboola-adapter-<version>.esa
```

## Running the tests

Adapter (JDK 8 or later, Maven 3):

```sh
cd keboola-adapter
mvn -q test
```

Integration flow scripts (Groovy 2.4.21, the Cloud Integration runtime's, on JDK 8 or later):

```sh
cd integration-flows/tests
mvn -q test
```

[`integration-flows/tests/README.md`](integration-flows/tests/README.md) lists the test
scenarios and says how to add a test for a script. The GitHub Actions workflow
[`.github/workflows/build.yml`](.github/workflows/build.yml) runs both test suites on every
pull request and on every push to `main` or a `release/` branch, and checks that every zip in
`dist/` matches its folder under `integration-flows/`.

## Changes in 1.1.0

Version 1.1.0 answers Keboola's review of 1.0.0; the ids are the review's. The Integration
Guide's change log lists the changes by flow.

- B1: the sign-in method to SAP S/4HANA is a Configure value, `S4_AUTH_METHOD`, on every receiver.
- B2, B3, M8, M9: the catalog flow reads SAP only through the receiver adapters: no probing with data reads, no cache, no embedded service list.
- B4: the user role of every endpoint is a Configure value, `SENDER_ROLE`; `?debug=1` answers only with `CATALOG_DIAGNOSTICS=true`.
- M1: the adapter is unchanged and optional; the delivery flow does not depend on it.
- M2, M3: the write path towards Keboola is unchanged and documented with the tested numbers.
- M4: a final failure of a delivery run ends Escalated, with its custom status kept.
- M5: `DELIVERY_ADDRESS` and `DELIVERY_SCHEDULE` are Configure values; a deploy starts no run.
- M6: `DELTA_OVERLAP_MINUTES` for incremental loading at second precision; watermark failures are named in the monitor.
- M7: `PAGE_SIZE` ships as 5000 and is capped at 20000.
- M10: unit tests for the Groovy scripts (`integration-flows/tests`) and the GitHub Actions build.
- Minor items: `CONNECTOR_PATH_PREFIXES`, explicit request-header lists on the receivers, the `CONNECTOR_ERROR` and `CATALOG_ERROR` codes, CSV dates without a time zone suffix.
