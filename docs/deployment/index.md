# Deployment Documentation Index

Status: current deployment documentation index.

## Scope

This folder contains current deployment-configuration references for repository-owned runtime profiles. It documents
configuration posture and non-claims. It does not approve production promotion, certify bank deployment, or replace
release governance.

## Current Sources

| Document | Scope |
| --- | --- |
| [Alert service config matrix](alert_service_config_matrix.md) | Profile-by-profile posture for transaction mode, trust refresh, external anchoring, auth, sensitive-read audit, bank guard, and trust-level status. |

## Related Documents

- [Configuration guide](../configuration/configuration_guide.md)
- [Release documentation](../release/index.md)
- [Release governance](../release/release_governance.md)
- [Security documentation](../security/index.md)
- [Runbook index](../runbooks/index.md)

## Interpretation Rules

- Deployment docs describe required posture, not evidence that an environment currently satisfies it.
- Production-like profiles must not use local, noop, in-memory, same-database, or disabled external evidence sinks.
- Bank-grade posture requires the current source-of-truth controls plus environment-specific release evidence.
- Local and smoke profiles are not regulator-grade deployment claims.
