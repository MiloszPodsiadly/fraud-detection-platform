## Local mTLS Fixtures

No private mTLS keys are committed here. Generate the local-only fixture set with:

```bash
bash scripts/bootstrap-local-fixtures.sh
```

Generated files are written to `deployment/.local/service-identity/mtls/`, which is ignored by Git and excluded
from Docker build contexts. Generated key files are read-only but readable by the non-root local Compose service
users so Linux bind mounts work; this is only for local or ephemeral CI demonstration fixtures. Identity mapping
uses SAN URI values including:

- `spiffe://fraud-platform/fraud-scoring-service`
- `spiffe://fraud-platform/alert-service`

CN-only identity is intentionally not accepted by FDP-18 mTLS service identity.
These fixtures must never be used in production or shared environments.
