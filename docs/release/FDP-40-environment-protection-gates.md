# FDP-40 Environment Protection Gates

## Required Gates

- Staging deploy requires approval.
- Production deploy requires approval.
- Production deploy requires dual control.
- Approver cannot be the same person as release author.
- Rollback owner is required.
- Security owner is required.
- Fraud ops owner is required.
- Platform owner is required.
- Deployment references immutable digest.
- Deployment references release manifest.
- Deployment references rollback plan.
- Deployment must not reference a fixture image.
- FDP-29 enablement requires a separate config PR.

These gates are platform enforcement requirements and do not claim production certification.
