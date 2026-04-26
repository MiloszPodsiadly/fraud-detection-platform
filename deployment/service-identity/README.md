# Local Service Identity Keys

These RS256 keys are for local Docker verification only.

The public JWKS is intentionally committed so the local RS256 Docker flow can
validate service tokens without external IAM or key discovery.

Do not use these keys in production. Production deployments must provide
environment-managed private keys and public JWKS material.
