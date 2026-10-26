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

## Symptoms and impact

- Setting `fraud.device-risk.admission.enabled=true` is expected to fail startup while the trusted issuer and signed
  coverage seal are absent. Do not bypass the validator.
- `fraud.scoring.engines.device.enabled=true` is also expected to fail startup. The candidate engine has no admitted
  evidence reader and must not affect Rules/ML decisions.
- No Device Risk collection, lifecycle metric, drain/recovery worker, query-plan measurement, or latency SLO is
  currently provided by the running application. p95/p99 and 10k/25k scan behavior are **NOT MEASURED**.

## Safe operator actions

- Keep both Device Risk flags disabled and use the documented baseline Rules/ML health checks.
- Treat a missing Device Risk diagnostic as expected while admission is closed.
- Keep generated replay data on isolated local/test topics. `--deterministic-test-mode` intentionally repeats IDs;
  never send that output to a shared or production topic.
- If an environment reports Device Risk observations or scores, stop rollout and verify which code/configuration
  produced them before relying on or deleting those records.

## Forbidden actions

- Do not override startup validation or treat `sourceSystem`, `trustedDevice`, caller proxy/VPN flags, or a
  structural `AUTHENTICATED_INSTITUTION` value as proof of issuer or physical-device identity.
- Do not present synthetic labels or diagnostic predicates as a calibrated fraud score, verified evidence, or a
  Rules/ML decision.
- Do not add raw customer, device, IP, token, commitment, or exception data to logs or metric labels.
- Do not manually mutate or remove observation records as a substitute for an approved retention/erasure process.

## Activation and escalation

Keep activation blocked until the independent issuer, scoped occurrence/device binding, signed source coverage,
privacy and retention approval, key custody, exact-payload delivery/recovery, consumer compatibility, and operational
tests have owners and evidence. Record query-plan/load measurements and operational metrics before claiming a runtime
SLO. Deployment requires separate environment approval and attestation; code presence and passing adapter tests are
not that approval.

Escalate attempted enablement or unexpected records to the platform owner and the authority responsible for issuer,
privacy, and retention approval. Preserve evidence until those owners determine its disposition.

## Non-claims

This implementation does not provide institutional authentication, physical-device verification, a production
ingress, a calibrated risk score, production telemetry, or a production-ready Device Risk runtime.
