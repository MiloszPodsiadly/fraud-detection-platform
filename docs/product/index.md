# Product Documentation Index

Status: current product documentation index.

## Current Product Sources

| Document | Scope |
| --- | --- |
| [Fraud Case Management](fraud_case_management.md) | Current product-domain source of truth for fraud-case triage, lifecycle, local idempotency, audit, and non-claims. |
| [Investigation Evidence Platform Roadmap Reconciliation](investigation_evidence_platform_roadmap.md) | Current FDP-81 roadmap reconciliation for the completed investigation evidence platform milestone and cleanup scope. |
| [Investigation Evidence Platform Cleanup Inventory](investigation_evidence_platform_cleanup_inventory.md) | Current FDP-81 cleanup inventory and proof-first deletion governance for investigation evidence platform artifacts. |
| [Fraud Case Evidence Summary](fraud_case_evidence_summary.md) | Current product-domain source of truth for the read-only fraud-case evidence summary projection and non-claims. |
| [Fraud Case Read Model Observability Contract](fraud_case_read_model_observability_contract.md) | Current FDP-79 backend observability contract for bounded fraud-case evidence summary and timeline read metrics. |
| [Fraud Case Read Surface Composition](fraud_case_read_surface_composition.md) | Current FDP-80 frontend composition contract for grouping read-only FraudCase investigation sections without behavior changes. |
| [Fraud Case Investigation Read Surface Contract](fraud_case_investigation_read_surface_contract.md) | Current FDP-78 frontend hardening contract for shared read-only FraudCase investigation section guardrails. |
| [Fraud Case Evidence Timeline UI](fraud_case_evidence_timeline_ui.md) | Current FDP-77 UI behavior contract for the frontend read-only fraud-case evidence timeline view and non-claims. |
| [Evidence Model](evidence_model.md) | Current product-domain semantics for typed evidence signals, status meanings, non-claims, and relationship to reason codes. |
| [Reason Codes](reason_codes.md) | Current product-domain semantics for scoring reason codes, legacy compatibility, UNKNOWN handling, and non-claims. |
| [Scoring Evidence Contract](scoring_evidence_contract.md) | Current product-domain semantics for typed scoring evidence, diagnostic evidence, compatibility, and attributes safety. |
| [Alert Evidence Snapshot](alert_evidence_snapshot.md) | Current product-domain semantics for point-in-time alert evidence snapshot projection, boundedness, lineage, and non-claims. |
| [Suspicious Transactions](suspicious_transactions.md) | Current product-domain semantics for the FDP-60 backend suspicious scoring signal read model and non-claims. |
| [SuspiciousTransaction Internal Read API](suspicious_transaction_read_api.md) | Current product-domain semantics for the protected FDP-62 internal read-only SuspiciousTransaction API. |
| [SuspiciousTransaction Internal UI](suspicious_transaction_internal_ui.md) | Current product-domain semantics for the FDP-66 internal read-only SuspiciousTransaction UI. |

## Related Technical Sources

- [Fraud Case API](../api/fraud_case_api.md)
- [Fraud case management architecture](../architecture/fraud_case_management_architecture.md)
- [Fraud case operations](../runbooks/fraud_case_operations.md)
- [Endpoint authorization map](../security/endpoint_authorization_map.md)

## Interpretation Rules

- Product documents describe current intended product behavior.
- Architecture documents describe module boundaries and implementation constraints.
- API documents describe externally visible HTTP behavior.
- FDP branch documents remain evidence for their branch unless this folder or another current source-of-truth
  document says otherwise.
