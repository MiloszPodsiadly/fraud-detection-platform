# FDP-42 - Local Audited Fraud Case Lifecycle Management

Status: branch proof summary.

## Scope

FDP-42 adds a local audited fraud case lifecycle workflow in `alert-service`:

- create fraud cases from alert ids
- assign or reassign investigators
- append notes and decisions
- transition, close, and reopen cases through `FraudCaseTransitionPolicy`
- search paginated cases
- read append-only case audit history

## Non-Claims

FDP-42 is not the regulated mutation coordinator flow, not FDP-29 evidence gated finalize, not lease-fenced, not
replay-safe, not external finality, and not WORM storage, legal notarization, or bank certification.

## Safety Proof

- Mongo transaction integration tests cover case+audit commit and rollback with transaction-mode `REQUIRED`.
- Security tests run with filters enabled for `/api/v1/fraud-cases/**`.
- Audit append-only architecture tests block direct audit mutation paths outside `FraudCaseAuditService.append`.
- Documentation no-overclaim tests block replay, finality, WORM, and unconditional rollback claims.
- CI job: `fdp42-fraud-case-management`.
