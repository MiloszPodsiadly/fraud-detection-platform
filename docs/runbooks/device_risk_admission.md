# Device Risk Admission Runbook

## Purpose and scope

Use this runbook for the disabled Device Risk observation and diagnostic candidate. The Mongo adapter and pure policy
are not connected to the running pipeline. This runbook does not authorize data collection or runtime activation.

## Current status

| Status | Evidence |
| --- | --- |
| IMPLEMENTED | Bounded Mongo adapter foundation, diagnostic-only predicates, and synthetic replay identity controls. |
| TESTED | Focused policy, persistence-adapter, contract, and deterministic replay cases; structural fixtures do not prove issuer authenticity. |
| DISABLED BY DESIGN | Admission defaults to `false`; enabling it fails startup. Device Risk is not registered in the scoring engine. |
| EXTERNAL DEPENDENCY | Authenticated issuer and coverage seal, occurrence/device binding, approved key custody and retention, durable delivery and recovery. |
| DEPLOYMENT ATTESTATION REQUIRED | Consumer compatibility, access controls, retention, recovery ownership, and measured performance. |

Binary decision: `CODE-FOUNDATION-MERGE-READY`; `DEVICE-RUNTIME-ACTIVATION-NO-GO`. No real authenticated
institutional issuer is available in the current ingress path. A structural enum, hash, request field, local fixture,
or self-signed seal does not change that result.

Accountable role owners and the concrete evidence required for each of the five candidate signals are listed in the
[Device Risk evidence boundaries](../architecture/device_risk_evidence_boundaries.md#activation-owners-and-evidence-required).
The repository contains no named approvers or recorded external approvals; keep both admission and scoring flags off.

## Symptoms and impact

- Setting `fraud.device-risk.admission.enabled=true` is expected to fail startup while the trusted issuer and signed
  coverage seal are absent. Do not bypass the validator.
- `fraud.scoring.engines.device.enabled=true` is also expected to fail startup. The candidate engine has no admitted
  evidence reader and must not affect Rules/ML decisions.
- The running application exports no Device Risk query, lifecycle, conflict, or latency metrics. An opt-in local
  Mongo query-plan/load measurement is recorded below; lock conflicts, transaction retry counts, and worst-case retry
  latency remain **NOT MEASURED**.
- `revokeDeviceFromOperationalHistory` in the un-wired adapter removes active membership and blocks replay, but
  intentionally retains bounded receipts. It is operational revocation, not legal or complete erasure.

## Opt-in local Mongo measurement

The reproducible Testcontainers measurement is opt-in and uses a disposable single-member Mongo replica set with
`mongo:7.0`. It seeds 10,000 device-window rows, 25,000 customer-window rows (with overlap), and 2,000 distinct
customers for the queried device. It warms each query three times, samples each query 30 times, runs `explain` with
execution statistics, and commits one occurrence at the configured caps. It emits only aggregate counts, index names,
timings, and Mongo resident-memory samples; no fixture identifiers are printed.

Run from the repository root in PowerShell:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dfraud.device-risk.performance=true" -pl feature-enricher-service -am `
  "-Dtest=MongoDeviceObservationStoreIntegrationTest#boundedPreEventQueriesUseExpectedIndexesAtConfiguredCapacity" test
```

One run on 2026-10-28 used Windows 11, OpenJDK 21.0.10, Docker Desktop 29.5.2 (reported 15,619 MB total),
Testcontainers 2.0.5, and the Mongo 7.0 single-member replica set:

| Operation | Index / scan | p50 | p95 | p99 | Serial throughput |
| --- | --- | ---: | ---: | ---: | ---: |
| Device window, 10,000 rows | `observation_device_window`; 10,000 keys / 10,000 docs examined | 44.8 ms | 52.8 ms | 56.7 ms | 21.80 queries/s |
| Customer window, 25,000 rows | `observation_customer_window`; 25,000 keys / 25,000 docs examined | 107.6 ms | 113.2 ms | 122.5 ms | 9.34 queries/s |

Seed insertion took 677 ms; the one transaction committing at both query caps took 233 ms. Mongo's `serverStatus`
resident-memory samples were 166 MB before seeding and 230 MB after the run; these are two point samples, not peak
container or JVM memory. The measurement did not induce contention. Lock conflicts and transaction retry counts are
not instrumented, and a worst-case retry duration was not measured. These local timings validate index selection and
exercise the configured row bounds; they are not a production throughput or latency SLO.

Separate focused Testcontainers tests cover parallel staging and duplicate seals, two-worker commits, commit versus
operational revocation, capacity quarantine, source gaps, expiry/cold windows, key rotation, and replay after
revocation. Those tests establish selected correctness interleavings, not a contention benchmark. The Mongo adapter
and its metrics remain unwired in production.

## Safe operator actions

- Keep both Device Risk flags disabled and use the documented baseline Rules/ML health checks.
- Treat a missing Device Risk diagnostic as expected while admission is closed.
- Keep generated replay data on isolated local/test topics. `--deterministic-test-mode` intentionally repeats IDs;
  never send that output to a shared or production topic. Its explicit
  `deviceEvidenceProvenance=SYNTHETIC_UNTRUSTED` marker is not a credential.
- If an environment reports Device Risk observations or scores, stop rollout and verify which code/configuration
  produced them before relying on or deleting those records.

### Clean-schema local/test reset

`DEVICE_STORE_SCHEMA_INCOMPATIBLE` is fail-closed. It is never a request for the application to migrate, drop an
index, rewrite data, or recount a counter. Only after stopping every user of a dedicated local/dev/test database may
an approved operator reset these Device Risk-only collections: `device_observation_candidates`,
`device_observations`, `device_occurrences`, `device_source_coverage`, `device_scope_fences`,
`device_operational_revocation_tombstones`, and historical `device_erasure_tombstones`. Do not use this procedure for
shared, production, retained, alert, audit, governance, or model data; escalate those cases to the data owner.

## Forbidden actions

- Do not override startup validation or treat `sourceSystem`, `trustedDevice`, caller proxy/VPN flags, or a
  structural `AUTHENTICATED_INSTITUTION` value as proof of issuer or physical-device identity.
- Do not present synthetic labels or diagnostic predicates as a calibrated fraud score, verified evidence, or a
  Rules/ML decision.
- Do not add raw customer, device, IP, token, commitment, or exception data to logs or metric labels.
- Do not manually mutate or remove observation records as a substitute for an approved retention/erasure process.
- Do not call operational revocation “erasure” or claim it removes retained occurrence/candidate receipts.

## Activation and escalation

Keep activation blocked until the independent issuer, scoped occurrence/device binding, signed source coverage,
privacy and retention approval, key custody, exact-payload delivery/recovery, consumer compatibility, and operational
tests have owners and evidence. The only potentially interpretable history predicates are bounded device novelty and
device switching; shared-device, device-country and proxy/VPN provider signals remain unsupported, and none carries a
calibrated fraud score. Record query-plan/load measurements and operational metrics before claiming a runtime SLO.
Deployment requires separate environment approval and attestation; code presence and passing adapter tests are not that
approval.

Escalate attempted enablement or unexpected records to the platform owner and the authority responsible for issuer,
privacy, and retention approval. Preserve evidence until those owners determine its disposition.

## Non-claims

This implementation does not provide institutional authentication, physical-device verification, a production
ingress, a calibrated risk score, production telemetry, or a production-ready Device Risk runtime.
