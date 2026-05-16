# Configuration Documentation Index

Status: current configuration documentation index.

## Scope

This folder contains current configuration posture documentation. It explains how local, test, production-like,
fixture, release-image, and enablement config modes must be interpreted. It does not approve production enablement or
replace environment-specific release approval.

## Current Sources

| Document | Scope |
| --- | --- |
| [Configuration guide](configuration_guide.md) | Mode/profile truth table, regulated mutation settings, messaging/outbox posture, release governance settings, and production-like review checklist. |

## Related Documents

- [Deployment documentation](../deployment/index.md)
- [Release documentation](../release/index.md)
- [Security documentation](../security/index.md)
- [Runbook index](../runbooks/index.md)

## Interpretation Rules

- `READY_FOR_ENABLEMENT_REVIEW` does not mean `PRODUCTION_ENABLED`.
- Fixture images and fixture profiles must not be described as release images or release profiles.
- `OFF` transaction mode is compatibility/demo behavior for regulated paths.
- `REQUIRED` transaction mode is expected for bank/prod-style regulated mutation safety.
- FDP-29 evidence-gated finalize requires a separate config PR and explicit release approval.
