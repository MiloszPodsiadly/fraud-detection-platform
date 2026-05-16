# Documentation Naming Map

Status: current naming refactor map.

## Scope

This map records current/global documentation moves made for naming consistency. FDP branch content remains
branch evidence, but filenames are normalized to lowercase `fdp_*` where needed.

## Moved Current Docs

Repository documentation now uses `snake_case` names. Uppercase branch identifiers such as `FDP-40` remain valid
inside document text, but filenames use lowercase underscores.

| Old path | New path | Reason |
| --- | --- | --- |
| `docs/api_error_contract.md` | `docs/api/api_error_contract.md` | API docs belong under `docs/api/`. |
| `docs/api_surface_v1.md` | `docs/api/api_surface_v1.md` | API docs belong under `docs/api/`. |
| `docs/operations_observability_v2.md` | `docs/observability/operations_observability_v2.md` | Observability docs belong under `docs/observability/`. |
| `docs/security_foundation_v1.md` | `docs/security/security_architecture.md` | Security docs belong under `docs/security/`; the current file is an architecture overview, not a versioned branch note. |
| `docs/service_identity_fdp17.md` | `docs/security/internal_service_identity.md` | JWT service identity is folded into the current service identity contract. |
| `docs/service_identity_fdp18.md` | `docs/security/internal_service_identity.md` | mTLS service identity is folded into the current service identity contract. |
| `docs/ml_governance_drift_v1.md` | `docs/ml/ml_governance_drift_v1.md` | ML governance belongs under `docs/ml/`. |
| `docs/fdp/fdp_25_regulated_mutation_commit.md` | `docs/fdp/fdp_25_regulated_mutation_commit.md` | FDP branch filename casing normalized. |
| `docs/architecture/alert_service_failure_windows.md` | `docs/architecture/alert_service_failure_windows.md` | Already followed the current convention. |
| `docs/architecture/alert_service_source_of_truth.md` | `docs/architecture/alert_service_source_of_truth.md` | Already followed the current convention. |
| `docs/architecture/alert_service_write_path_inventory.md` | `docs/architecture/alert_service_write_path_inventory.md` | Already followed the current convention. |
| `docs/deployment/alert_service_config_matrix.md` | `docs/deployment/alert_service_config_matrix.md` | Already followed the current convention. |
| `docs/observability/alert_service_slo.md` | `docs/observability/alert_service_slo.md` | Already followed the current convention. |

## Non-Claims

Renaming documentation does not change API behavior, runtime behavior, release approval, or production readiness.
