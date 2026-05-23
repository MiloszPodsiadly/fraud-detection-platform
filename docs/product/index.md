# Product Documentation Index

Status: current product documentation index.

## Current Product Sources

| Document | Scope |
| --- | --- |
| [Fraud Case Management](fraud_case_management.md) | Current product-domain source of truth for fraud-case triage, lifecycle, local idempotency, audit, and non-claims. |
| [Fraud Case Evidence Summary](fraud_case_evidence_summary.md) | Current product-domain source of truth for the read-only fraud-case evidence summary projection and non-claims. |
| [Fraud Case Evidence Timeline UI](fraud_case_evidence_timeline_ui.md) | Current product-domain source of truth for the frontend read-only fraud-case evidence timeline view and non-claims. |
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
