# Local Service Identity Material

Private cryptographic key material is not committed in this directory.

Run `bash scripts/bootstrap-local-fixtures.sh` from the repository root to generate local mTLS certificate/key
pairs, JWT signing key pairs, and JWKS under `deployment/.local/service-identity/`. The Docker Compose
service-identity overlays mount that generated directory only.

The generated material is ignored by Git and excluded from Docker build contexts. It exists for local Docker
evaluation and fixture-dependent tests only; it is not production PKI, production secret management, production
provenance, or an independent external trust anchor.

The bootstrap script makes generated fixture files read-only but readable by the non-root Docker Compose service
users because Linux bind mounts preserve host ownership and mode bits. This is acceptable only for generated,
local-only or ephemeral CI demonstration material. Do not reuse or share these keys.

Production deployments must use externally managed private keys, JWKS, CA material, and service certificates.
