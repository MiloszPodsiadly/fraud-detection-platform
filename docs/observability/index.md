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
| [Fraud-case lifecycle idempotency dashboard](fraud_case_lifecycle_idempotency_dashboard.md) | Current dashboard contract for local fraud-case lifecycle idempotency outcomes. |

## Branch Evidence

| Document | Scope |
| --- | --- |
| [FDP-33 lease renewal dashboard](fdp_33_lease_renewal_dashboard.md) | Lease renewal observability contract for FDP-33 branch evidence. |
| [FDP-35 regulated mutation dashboard spec](fdp_35_regulated_mutation_dashboard_spec.md) | Dashboard contract for modeled restart/recovery proof. |
| [FDP-35 regulated mutation alert thresholds](fdp_35_regulated_mutation_alert_thresholds.md) | Threshold contract for modeled restart/recovery proof. |

## Interpretation Rules

- Metrics are runtime health signals, not compliance reports.
- Metric labels must stay bounded and low-cardinality.
- Dashboards do not approve production enablement.
- Operator actions belong in [runbooks](../runbooks/index.md).
- Release-review evidence belongs in [release documentation](../release/index.md) or branch evidence.
