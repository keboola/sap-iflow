# SAP S/4HANA Integration with Keboola

[![build](https://github.com/keboola/sap-iflow/actions/workflows/build.yml/badge.svg)](https://github.com/keboola/sap-iflow/actions/workflows/build.yml)

SAP Integration Suite content that connects SAP S/4HANA to the Keboola data platform,
published on the SAP Business Accelerator Hub as the integration package
*SAP S/4HANA Integration with Keboola*.

## Contents

| Artifact | ID | Version | Required | Purpose |
|---|---|---|---|---|
| Query Available Services from SAP S4HANA | `QueryAvailableServicesFromSAPS4HANA` | 1.0.0 | optional | Lists the OData services the SAP communication user can reach |
| Query Business Data from SAP S4HANA to Keboola | `KeboolaODataConnector` | 1.0.0 | required for the extractor | Read-only OData endpoint for Keboola's SAP extractor |
| Deliver Business Data from SAP S4HANA to Keboola | `DeliverBusinessDataFromSAPS4HANAToKeboola` | 1.0.0 | optional | Reads S/4HANA on a schedule or on request and writes into a Keboola Storage table |
| Keboola adapter | `Keboola` | 1.0.0 | optional | Receiver adapter that writes CSV into a Keboola Storage table |
| Value Mapping KeboolaServiceAddresses | `KeboolaServiceAddresses` | (added by 1.1.0) | optional | (added by 1.1.0) |

| Endpoint | Flow | Methods |
|---|---|---|
| `/http/keboola/catalog` | Query Available Services | `GET`, `HEAD` |
| `/http/keboola/connector/<OData path>` | Query Business Data | `GET`, `HEAD` |
| `/http/keboola/deliver` | Deliver Business Data (manual trigger) | `POST` |

## Layout

```
integration-flows/       integration flow sources, one folder per artifact
integration-flows/tests/ unit tests for the flows' Groovy scripts (Maven)
keboola-adapter/         receiver adapter source (Maven, SAP Adapter Development Kit)
dist/                    importable builds of the flows and the adapter
docs/                    Integration Guide (PDF)
.github/                 GitHub Actions build and Dependabot settings
```

## Requirements

- SAP Integration Suite with Cloud Integration; the delivery flow also needs JMS message queues
- SAP S/4HANA Cloud or on-premise, with a communication user and arrangements for the APIs to read
- A Keboola project; for the delivery flow, a Storage token with write access to the target bucket

## Installation

Setup, configuration, authentication and troubleshooting are covered in
[`docs/SAP-S4HANA-Integration-with-Keboola-Integration-Guide.pdf`](docs/SAP-S4HANA-Integration-with-Keboola-Integration-Guide.pdf).

1. Copy the package from the SAP Business Accelerator Hub, or create a package and add the
   three zips from `dist/` as integration flows and `dist/keboola-adapter-1.0.0.esa` as an
   integration adapter.
2. Deploy the security material: `KEBOOLA_S4` for SAP S/4HANA and, for the delivery flow
   and the adapter, `KEBOOLA_STORAGE` holding the Keboola Storage token.
3. Set the Configure values of each flow. Every system-specific value ships empty.
4. Deploy.

## Building from source

Integration flows:

```sh
cd integration-flows/<ArtifactId>
zip -r -X ../../dist/<ArtifactId>.zip . -x '.DS_Store' '*/.DS_Store'
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

[`integration-flows/tests/README.md`](integration-flows/tests/README.md) says how to add a
test for a script. The GitHub Actions workflow
[`.github/workflows/build.yml`](.github/workflows/build.yml) runs both test suites on every
push and pull request and checks that every zip in `dist/` matches its folder under
`integration-flows/`.
