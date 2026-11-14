# Device Risk Admission Runbook

## Purpose and scope

Use this runbook for the Device Risk trust path. Independently signed admission and private diagnostics are implemented
behind package-private test-only adapters. They are exercised against real Mongo/Kafka test infrastructure but are not
registered as production beans, endpoints, or scoring paths. This runbook does not authorize data collection or runtime
activation.

## Current status

| Status | Evidence |
| --- | --- |
| IMPLEMENTED IN TEST-ONLY PATH | Independent source, Device and coverage signature verification; epoch-aware Mongo v2 stage/seal/commit; exact initial SCORED binding; private read-only diagnostic. |
| TESTED | Ed25519 test signers, authenticated Mongo replica sets and Kafka Testcontainers cover selected lifecycle, recovery, revocation, authorization, privacy and contract boundaries. These do not prove production issuer authenticity or population completeness. |
| DISABLED BY DESIGN | `fraud.device-risk.admission.enabled=false` and `fraud.scoring.engines.device.enabled=false`; both services fail startup if their respective flag is enabled without authorization. Device remains absent from the active registry. |
| EXTERNAL DEPENDENCY | Production issuer and population authority, approved key custody, legal/privacy retention, production Mongo roles and additive schema/index migration, operational ownership and external monitoring. |
| DEPLOYMENT ATTESTATION REQUIRED | Consumer compatibility, access controls, retention, recovery ownership, and measured performance. |

Status: code-merge decision is pending review; Device runtime activation is **NO-GO**. No production issuer or
population authority is configured. Test signers are disposable keys generated in memory and do not establish a
production trust anchor. A structural enum, hash, request field, local fixture, or self-signed test assertion does not
change that result. The current design and release gates are in [Device Verified Source Admission](../adr/device_verified_source_admission.md).

The pure policy's supported and unsupported signals are described in [Device Risk signal policy](../architecture/device_risk_signal_policy.md).
The repository contains no named approvers or recorded external approvals; keep both admission and scoring flags off.

## Symptoms and impact

- Setting `fraud.device-risk.admission.enabled=true` is expected to fail startup while the trusted issuer and signed
  coverage seal are absent. Do not bypass the validator.
- `fraud.scoring.engines.device.enabled=true` is also expected to fail startup. The candidate engine has no admitted
  evidence reader and must not affect Rules/ML decisions.
- The running application exports no Device Risk query, lifecycle, conflict, or latency metrics. An opt-in local
  Stage A query-plan/load measurement is recorded below. The serial profiles observed zero transient transaction
  retries and unknown commit acknowledgements; contention, lock-conflict behavior, worst-case retry latency and runtime
  telemetry remain **NOT MEASURED**.
- `revokeDeviceFromOperationalHistory` in the test-only adapter removes active membership and blocks replay, but
  intentionally retains bounded receipts. It is operational revocation, not legal or complete erasure.

## Historical and current Mongo measurements

The table below is a historical local baseline from 2026-10-28 against the earlier implementation. It is retained as
historical evidence only; it is not a performance result for the current epoch-aware v2 schema. Current v2 Stage A
measurements at 100 and 500 history members are recorded separately below. The 1,000-member Stage A profile, the
10,000/25,000 high-density limits, saturation, contention, and production capacity/SLO are **NOT MEASURED**.

### Current Stage A local profiles

The opt-in profile uses real signed admission fixtures and an authenticated single-member Mongo replica set in
Testcontainers. Both completed runs were recorded on 2026-11-13 using Windows 11, OpenJDK 21.0.10, Docker Desktop
29.5.2 (15,619 MB reported), Testcontainers 2.0.5 and MongoDB 7.0. On the 100-member run, Stage A `stage` p50/p95
was 30.059/39.310 ms, `seal` took 46.948 ms (one
sample), and `commit` p50/p95/p99 was 73.806/105.651/118.013 ms. It recorded 1,000 Device reads / 20,100 Device
documents and 2,196 source reads / 21,600 source documents. On the completed 500-member run, `stage` p50/p95 was
26.416/35.294 ms, warm `seal` p50/p95 was 30.988/53.746 ms, and warm `commit` p50/p95/p99 was
135.234/216.839/286.730 ms (maximum 523.980 ms). It recorded 5,000 Device reads / 182,896 Device documents and
10,996 source reads / 508,000 source documents. Both runs had zero quarantine/fail-closed outcomes, transient
transaction retries and unknown commit acknowledgements.

The four real Stage A query shapes are the observation device/customer `$or` union, anchors, occurrences, and committed
candidates. At 500, each used the expected device/customer window indexes, returned and examined 500 documents, and
examined 1,000 keys; there was no in-memory `SORT` or `COLLSCAN`. Stage A sorts customer-matched observation rows in
Java after the indexed union query. The profile checks local query behavior and fixture latency only; it does not prove
contention behavior or a production SLO. Revalidating the retained history for each new event makes total work grow
with the sum of prior window sizes for a repeated device/customer stream. Further safe reduction needs a separately
reviewed authenticated incremental-history design with explicit source-revocation and deletion semantics. Do not
change the current history caps based on these two profiles. Stage B has separate local 100/500/1,000-member results
in [private diagnostics](../architecture/device_private_diagnostics.md).

Run either completed profile from the repository root in PowerShell:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dfraud.device-risk.performance=true" "-Dfraud.device-risk.stage-a.max-members=100" `
  -pl feature-enricher-service -am "-Dtest=MongoDeviceObservationStageABenchmarkTest" test
```

Use `-Dfraud.device-risk.stage-a.max-members=500` for the 500-member run. The 1,000-member run was stopped after its
500-member checkpoint and is **NOT MEASURED**; do not treat that partial run as a completed result. No 10,000/25,000
profile or production capacity/SLO measurement is available.

### Historical opt-in local baseline

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
container or JVM memory. The measurement did not induce contention. These historical timings validate the earlier
implementation's index selection and exercise its configured row bounds; they are not a production throughput or
latency SLO.

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
an approved operator reset these seven Device Risk-only collections: `device_observation_candidates`,
`device_observations`, `device_occurrences`, `device_history_member_anchors`, `device_source_coverage`,
`device_scope_fences`, and `device_operational_revocation_tombstones`. Do not use this procedure for
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
