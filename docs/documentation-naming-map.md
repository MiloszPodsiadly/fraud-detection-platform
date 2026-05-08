# Documentation Naming Map

Status: current naming refactor map.

## Scope

This map records current/global documentation moves made for naming consistency. Historical FDP content remains
historical, but filenames are normalized to lowercase `fdp-*` where needed.

## Moved Current Docs

Repository documentation now uses `lower-kebab` names. Uppercase branch identifiers such as `FDP-40` remain valid
inside document text, but not in filenames.

| Old path | New path | Reason |
| --- | --- | --- |
| `docs/api-error-contract.md` | `docs/api/api-error-contract.md` | API docs belong under `docs/api/`. |
| `docs/api-surface-v1.md` | `docs/api/api-surface-v1.md` | API docs belong under `docs/api/`. |
| `docs/operations-observability-v1.md` | `docs/observability/operations-observability-v1.md` | Observability docs belong under `docs/observability/`. |
| `docs/operations-observability-v2.md` | `docs/observability/operations-observability-v2.md` | Observability docs belong under `docs/observability/`. |
| `docs/security-foundation-v1.md` | `docs/security/security-foundation-v1.md` | Security docs belong under `docs/security/`. |
| `docs/service-identity-fdp17.md` | `docs/security/service-identity-fdp-17.md` | Service identity is security documentation. |
| `docs/service-identity-fdp18.md` | `docs/security/service-identity-fdp-18.md` | Service identity is security documentation. |
| `docs/ml-governance-drift-v1.md` | `docs/ml/ml-governance-drift-v1.md` | ML governance belongs under `docs/ml/`. |
| `docs/fdp-25-regulated-mutation-commit.md` | `docs/fdp-25-regulated-mutation-commit.md` | Historical FDP filename casing normalized. |
| `docs/architecture/alert-service-failure-windows.md` | `docs/architecture/alert-service-failure-windows.md` | Already followed the current convention. |
| `docs/architecture/alert-service-source-of-truth.md` | `docs/architecture/alert-service-source-of-truth.md` | Already followed the current convention. |
| `docs/architecture/alert-service-write-path-inventory.md` | `docs/architecture/alert-service-write-path-inventory.md` | Already followed the current convention. |
| `docs/deployment/alert-service-config-matrix.md` | `docs/deployment/alert-service-config-matrix.md` | Already followed the current convention. |
| `docs/observability/alert-service-slo.md` | `docs/observability/alert-service-slo.md` | Already followed the current convention. |

## Non-Claims

Renaming documentation does not change API behavior, runtime behavior, release approval, or production readiness.
