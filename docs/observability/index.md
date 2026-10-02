# Observability Documentation Index

Status: current observability documentation index.

## Scope

This folder contains current observability contracts and branch evidence for dashboard or threshold review. Current
documents describe operational interpretation. FDP-numbered documents are retained as branch evidence unless a current
source-of-truth document says otherwise.

## Current Observability Sources

| Document | Scope |
| --- | --- |
| [Alert service SLOs](alert_service_slo.md) | Current alert-service operational health signals and interpretation rules. |
| [Operations and observability v2](operations_observability_v2.md) | Current ML, governance, audit, internal-auth, and platform observability reference. |

## Branch Evidence

| Document | Scope |
| --- | --- |
| [Regulated mutation lease renewal dashboard](regulated_mutation_lease_renewal_dashboard.md) | Lease renewal observability contract. |
| [Regulated mutation dashboard](regulated_mutation_dashboard.md) | Dashboard contract for modeled restart/recovery proof. |
| [Regulated mutation alert thresholds](regulated_mutation_alert_thresholds.md) | Threshold contract for modeled restart/recovery proof. |
| [Fraud-case lifecycle idempotency dashboard](fraud_case_lifecycle_idempotency_dashboard.md) | Historical FDP-44 artifact; its emitter was removed by FDP-81. |

## Interpretation Rules

- Metrics are runtime health signals, not compliance reports.
- Metric labels must stay bounded and low-cardinality.
- Dashboards do not approve production enablement.
- Operator actions belong in [runbooks](../runbooks/index.md).
- Release-review evidence belongs in [release documentation](../release/index.md) or branch evidence.
