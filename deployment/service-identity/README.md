# Local Service Identity Keys

These RS256 keys are for local Docker verification only.

The public JWKS is intentionally committed so the local RS256 Docker flow can
validate service tokens without external IAM or key discovery.

They are committed intentionally for local development and verification only.

They must NEVER be used in any production or shared environment.

Production deployments must use externally managed private keys and JWKS
material.
