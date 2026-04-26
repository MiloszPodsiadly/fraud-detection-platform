# FDP-17 Service Identity

FDP-17 adds a production-target internal service identity mode for configured calls into `ml-inference-service`.

## Scope

Protected internal paths are unchanged:

- `fraud-scoring-service` calls `ml-inference-service` scoring with `ml-score`.
- `alert-service` calls `ml-inference-service` governance endpoints with `governance-read`.

FDP-17 does not change scoring behavior, ML model behavior, Kafka contracts, governance workflow, retraining, rollback, alert triggering, or SLA enforcement.

## Modes

- `DISABLED_LOCAL_ONLY`: explicit local/dev/test/docker-local bypass. This mode is forbidden in prod-like profiles.
- `TOKEN_VALIDATOR`: compatibility shared-token mode. In prod-like profiles it requires `INTERNAL_AUTH_ALLOW_TOKEN_VALIDATOR_IN_PROD=true`, token hash mode, and an allowlist.
- `JWT_SERVICE_IDENTITY`: HMAC-signed JWT service identity mode. The server validates issuer, audience, expiration, signature, service identity, service allowlist, and required authority.
- `MTLS_READY`: fail-closed configuration boundary only. Full enterprise mTLS is not implemented.

Unknown modes fail startup instead of downgrading.

## JWT Contract

Server-side ML configuration:

- `INTERNAL_AUTH_MODE=JWT_SERVICE_IDENTITY`
- `INTERNAL_AUTH_JWT_ISSUER`
- `INTERNAL_AUTH_JWT_AUDIENCE`
- `INTERNAL_AUTH_JWT_SECRET`
- `INTERNAL_AUTH_JWT_SERVICE_CLAIM=service_name`
- `INTERNAL_AUTH_JWT_AUTHORITIES_CLAIM=authorities`
- `INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES=fraud-scoring-service:ml-score,alert-service:governance-read`

Java client configuration:

- `INTERNAL_AUTH_CLIENT_ENABLED=true`
- `INTERNAL_AUTH_CLIENT_MODE=JWT_SERVICE_IDENTITY`
- `INTERNAL_AUTH_SERVICE_NAME`
- `INTERNAL_AUTH_JWT_ISSUER`
- `INTERNAL_AUTH_JWT_AUDIENCE`
- `INTERNAL_AUTH_JWT_SECRET`
- `INTERNAL_AUTH_JWT_TTL`
- `INTERNAL_AUTH_JWT_AUTHORITIES`

JWT claims:

- `iss`
- `aud`
- `iat`
- `exp`
- `service_name`
- `authorities`

Business clients do not construct JWTs directly. Java clients call `InternalServiceAuthHeaders`, which delegates JWT creation to the internal credential provider.

## Failure Behavior

- Missing credentials return `401`.
- Expired JWTs return `401`.
- Invalid signature, issuer, audience, unknown service, or missing authority return `403`.
- Auth failure logs a structured warning without token contents.
- Metrics remain low-cardinality:
  - `fraud_internal_auth_success_total{source_service,target_service}`
  - `fraud_internal_auth_failure_total{target_service,reason}`

Allowed failure reasons are bounded and do not include tokens, subjects, paths, exception messages, actor IDs, resource IDs, or audit IDs.

## Docker Verification

JWT mode can be exercised with:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-jwt.yml up --build -d
```

Expected checks:

- anonymous direct `POST /v1/fraud/score` returns `401`
- anonymous direct `GET /governance/advisories` returns `401`
- invalid bearer token returns `403`
- wrong-audience JWT returns `403`
- scoring through `fraud-scoring-service` succeeds
- governance reads through `alert-service` succeed
- auth metrics use only bounded labels
- logs do not include token contents

## Limitations

This is a service-auth foundation. It is not enterprise IAM, not full mTLS, not zero-trust certification, not WORM storage, not SIEM integration, and not a compliance archive.
