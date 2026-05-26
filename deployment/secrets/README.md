# Local Secrets And Production Injection

`deployment/.env` is a committed local-only runtime fixture so the repository
starts with one command. `deployment/.env.example` documents the same variable
surface for replacement in environments that do not use the local fixture.

Local demo secrets are not production secrets.

Production-like deployments must replace:

- `INTERNAL_AUTH_TOKEN` and `INTERNAL_AUTH_JWT_SECRET`
- `TRUST_AUTHORITY_HMAC_SECRET`
- `GRAFANA_ADMIN_PASSWORD` and `KEYCLOAK_ADMIN_PASSWORD`

The applications currently read these values from environment variables, so an
orchestrator should inject them from its secret store rather than committing
secret files. Existing RS256 and mTLS files under `deployment/service-identity/`
are committed local verification fixtures only; use externally managed private
keys and certificates outside local development.
