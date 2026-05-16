# Alert Service Config Matrix

Status: current deployment configuration reference.

## Scope

This matrix summarizes deployment-profile posture for `alert-service` configuration. It is a configuration reference,
not a production approval, bank certification, external platform attestation, or proof that an environment is safe to
promote. Release readiness and external platform controls are documented under [release documentation](../release/index.md).

| Profile | Transaction mode | Trust refresh | External anchoring | Trust Authority signing | Auth | Sensitive read audit | Bank guard | Trust-level status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| local | OFF by default | PARTIAL allowed | optional | optional | demo or JWT by explicit opt-in | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| test | OFF/REQUIRED by test | PARTIAL allowed | optional | optional | test-controlled | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| docker-local | OFF/REQUIRED by compose | PARTIAL allowed | disabled by default | disabled by default | demo or local OIDC override | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| bank-local | OFF/REQUIRED by explicit smoke config | PARTIAL allowed | disabled allowed | disabled allowed | demo allowed | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| staging | REQUIRED | ATOMIC | enabled, required, fail-closed | required | JWT required, demo forbidden | fail closed | strict | BANK_PROFILE_ACTIVE when bank fail-closed enabled |
| prod | REQUIRED | ATOMIC | enabled, required, fail-closed | required | JWT required, demo forbidden | fail closed | strict | BANK_PROFILE_ACTIVE when bank fail-closed enabled |
| bank | REQUIRED | ATOMIC | enabled, required, fail-closed | required | JWT required, demo forbidden | fail closed | strict | BANK_PROFILE_ACTIVE |

`bank` means the strict bank-grade runtime closure gate. It requires FDP-24 external anchoring publication to be enabled, required, and fail-closed; the sink must be production-capable and not `disabled`, `noop`, `local-file`, `in-memory`, or `same-database`. `bank-local` is smoke-only and must not be described as regulator-grade.

Prod-like profiles must not use local/noop/in-memory/same-database external evidence sinks. FDP-27 is a production closure gate; it does not add KMS/HSM, legal notarization, WORM storage, broker-side verification, or a pre-commit/finalize redesign.
