# FDP-40 Environment Protection Gates

## Required Gates

- Staging deploy requires approval.
- Production deploy requires approval.
- FDP-40 uses the single release owner model.
- FDP-40 does not require dual-control.
- Release owner is required and must be named.
- Required checks must be green.
- Rollback owner is required.
- Security review evidence is required.
- Fraud Ops review evidence is required.
- Platform review evidence is required.
- Operator drill evidence is required.
- Deployment references immutable digest.
- Deployment references release manifest.
- Deployment references rollback plan.
- Deployment must not reference a fixture image.
- FDP-29 enablement requires a separate config PR.

These gates are platform readiness requirements and do not claim production certification.
Production enablement remains false until a separate config PR is reviewed and approved.
