# FDP-40 Registry Promotion Policy

Production promotion must be based on an immutable digest, not a mutable tag.

## Requirements

- Promotion uses immutable digest.
- Mutable tag alone is NO-GO.
- Release tag is non-overwritable.
- Fixture image cannot be promoted.
- Release image digest matches FDP-39 proof.
- Signed or attested release image digest matches release manifest.
- Rollback references immutable digest.
- Promotion evidence includes registry repository, digest, and timestamp.
- Registry immutability is verified or the release is NOT_READY_FOR_PRODUCTION_ENABLEMENT.

Absence of registry immutability is a platform gap and does not claim production readiness.
