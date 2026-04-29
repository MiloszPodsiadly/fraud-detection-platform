# Local Service Identity Material

These RS256 keys and mTLS certificate fixtures are for local Docker verification only.

The public JWKS is intentionally committed so the local RS256 Docker flow can
validate service tokens without external IAM or key discovery.

`docker-compose.trust-authority-jwt.yml` mounts this JWKS into
`audit-trust-authority` and mounts only `alert-service-private.pem` into
`alert-service`, preserving the local client-private/server-public split used by
the FDP-23 trust-authority JWT smoke flow.

They are committed intentionally for local development and verification only.

They must NEVER be used in any production or shared environment.

Production deployments must use externally managed private keys and JWKS
material, plus externally managed CA and service certificate material for mTLS.

The mTLS fixtures live under `deployment/service-identity/mtls/` and use SAN URI
identity for local FDP-18 verification. They must never be reused outside local
development.
