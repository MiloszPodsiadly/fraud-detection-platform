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
- `JWT_SERVICE_IDENTITY`: signed JWT service identity mode. `RS256` is the production-target algorithm. `HS256` remains local compatibility only and is rejected in prod-like profiles.
- `MTLS_READY`: fail-closed compatibility boundary.
- `MTLS_SERVICE_IDENTITY`: implemented in FDP-18 as certificate-backed internal service identity. See `docs/service-identity-fdp18.md`.

Unknown modes fail startup instead of downgrading.

JWT validation is implemented with PyJWT in `ml-inference-service`. Java service JWT generation uses Nimbus JOSE + JWT. Custom JWT parsing/signing is intentionally avoided.

`RS256` uses public-key validation on `ml-inference-service` and per-service private-key signing on Java clients. The ML server uses only JWKS public key material; it does not load service private keys.

## JWT Contract

Server-side ML configuration:

- `INTERNAL_AUTH_MODE=JWT_SERVICE_IDENTITY`
- `INTERNAL_AUTH_JWT_ALGORITHM=RS256`
- `INTERNAL_AUTH_JWT_ISSUER`
- `INTERNAL_AUTH_JWT_AUDIENCE`
- `INTERNAL_AUTH_JWKS_PATH` or `INTERNAL_AUTH_JWKS_JSON`
- `INTERNAL_AUTH_JWT_SERVICE_CLAIM=service_name`
- `INTERNAL_AUTH_JWT_AUTHORITIES_CLAIM=authorities`
- `INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES=fraud-scoring-service:ml-score,alert-service:governance-read`
- `INTERNAL_AUTH_ALLOWED_SERVICE_KEYS=fraud-scoring-service:scoring-key-1,alert-service:alert-key-1`
- `INTERNAL_AUTH_JWT_MAX_TOKEN_AGE_SECONDS=300`
- `INTERNAL_AUTH_JWT_MAX_ALLOWED_TTL_SECONDS=300`
- `INTERNAL_AUTH_JWT_CLOCK_SKEW_SECONDS=30`
- `INTERNAL_AUTH_REPLAY_CACHE_ENABLED=false`
- `INTERNAL_AUTH_REPLAY_CACHE_MODE=log` or `reject`
- `INTERNAL_AUTH_REPLAY_CACHE_MAX_ENTRIES=10000`

Java client configuration:

- `INTERNAL_AUTH_CLIENT_ENABLED=true`
- `INTERNAL_AUTH_CLIENT_MODE=JWT_SERVICE_IDENTITY`
- `INTERNAL_AUTH_SERVICE_NAME`
- `INTERNAL_AUTH_JWT_ALGORITHM=RS256`
- `INTERNAL_AUTH_JWT_KEY_ID`
- `INTERNAL_AUTH_JWT_PRIVATE_KEY_PATH` or `INTERNAL_AUTH_JWT_PRIVATE_KEY_PEM`
- `INTERNAL_AUTH_JWT_ISSUER`
- `INTERNAL_AUTH_JWT_AUDIENCE`
- `INTERNAL_AUTH_JWT_TTL`
- `INTERNAL_AUTH_JWT_AUTHORITIES`

JWT claims:

- JOSE header `alg=RS256`
- JOSE header `kid`
- `iss`
- `aud`
- `iat`
- `exp`
- `service_name`
- `authorities`

Service-to-key binding is enforced through `INTERNAL_AUTH_ALLOWED_SERVICE_KEYS`: for example, `kid=scoring-key-1` may sign only `service_name=fraud-scoring-service`, and `kid=alert-key-1` may sign only `service_name=alert-service`. A known key with a mismatched `service_name` is rejected.

HS256 compatibility still requires a local HMAC secret of at least 32 bytes, but it is not production-target and is forbidden in prod-like profiles.

Business clients do not construct JWTs directly. Java clients call `InternalServiceAuthHeaders`, which delegates JWT creation to the internal credential provider.

## Replay Risk and Mitigation

JWT service tokens can be replayed within their validity window if an attacker obtains a token. FDP-17 reduces that window by requiring short-lived tokens, strict `iat` and `exp` validation, maximum token age, maximum `exp - iat` TTL, and explicit clock skew handling.

`ml-inference-service` can also enable an optional in-memory soft replay cache. The cache stores only a hash of the token, is bounded by `INTERNAL_AUTH_REPLAY_CACHE_MAX_ENTRIES`, and expires entries at the token TTL. It is a local-process signal only; it does not provide distributed replay prevention across horizontally scaled instances.

This is not equivalent to mTLS, nonce-based replay prevention, or a zero-replay guarantee. Full replay protection requires `jti` plus a distributed store, or mTLS with channel binding.

## Failure Behavior

- Missing credentials return `401`.
- Expired JWTs return `401`.
- Missing or unknown `kid`, unsupported algorithms, `alg=none`, HS256 in RS256 mode, invalid signature, issuer, audience, unknown service, key/service mismatch, or missing authority return `403`.
- Auth failure logs a structured warning without token contents.
- Metrics remain low-cardinality:
  - `fraud_internal_auth_success_total{source_service,target_service,mode}`
  - `fraud_internal_auth_failure_total{target_service,mode,reason}`
  - `fraud_internal_auth_replay_rejected_total{reason}`
  - `fraud_internal_auth_token_age_seconds{reason}`

Allowed failure reasons are bounded and do not include tokens, subjects, paths, exception messages, actor IDs, resource IDs, or audit IDs.

## Docker Verification

Production-target RS256 mode can be exercised with:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-rs256.yml up --build -d
```

Expected checks:

- anonymous direct `POST /v1/fraud/score` returns `401`
- anonymous direct `GET /governance/advisories` returns `401`
- invalid bearer token returns `403`
- HS256 token in RS256 mode returns `403`
- unknown `kid` returns `403`
- wrong audience JWT returns `403`
- wrong key/service mismatch returns `403`
- scoring through `fraud-scoring-service` succeeds
- governance reads through `alert-service` succeed
- auth metrics use only bounded labels
- logs do not include token contents

## Limitations

`deployment/service-identity/` contains local development keys only. Do not use them in production.

This is a JWT service-auth foundation. FDP-18 adds internal mTLS in a separate contract. FDP-17 remains not enterprise IAM, not external JWKS discovery, not automated key rotation, not HSM/KMS integration, not zero-trust certification, not WORM storage, not SIEM integration, and not a compliance archive.
