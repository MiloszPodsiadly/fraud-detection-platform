# Alert Service Config Matrix

| Profile | Transaction mode | Trust refresh | Outbox publisher | Recovery | Dual control | Sensitive read audit | Bank guard | Trust-level status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| local | OFF by default | PARTIAL allowed | optional | optional | optional | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| test | OFF/REQUIRED by test | PARTIAL allowed | optional | optional | optional | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| docker-local | OFF/REQUIRED by compose | PARTIAL allowed | optional | optional | optional | best effort unless enabled | not strict | NON_BANK_LOCAL_MODE |
| staging | REQUIRED | ATOMIC | enabled | enabled | enabled | fail closed | strict | BANK_PROFILE_ACTIVE when bank fail-closed enabled |
| prod | REQUIRED | ATOMIC | enabled | enabled | enabled | fail closed | strict | BANK_PROFILE_ACTIVE when bank fail-closed enabled |
| bank | REQUIRED | ATOMIC | enabled | enabled | enabled | fail closed | strict | BANK_PROFILE_ACTIVE |

Prod-like profiles must not use local/noop/in-memory external evidence sinks when external publication is enabled or required. FDP-27 is a production closure gate; it does not add KMS/HSM, legal notarization, WORM storage, broker-side verification, or a pre-commit/finalize redesign.
