# Internal Service Identity

Internal service identity protects service-to-service calls into `ml-inference-service`. It is separate from browser/OIDC authentication and does not change fraud scoring, ML model behavior, Kafka contracts, governance workflow, retraining, rollback, alert triggering, or SLA enforcement.

## Protected Calls

| Source service | Target | Required authority |
| --- | --- | --- |
| `fraud-scoring-service` | `POST /v1/fraud/score` on `ml-inference-service` | `ml-score` |
| `alert-service` | ML governance advisory endpoints on `ml-inference-service` | `governance-read` |

`/health` remains public for runtime health checks. Production exposure of metrics and health endpoints must be controlled by deployment.

## Modes

| Mode | Purpose | Production-like behavior |
| --- | --- | --- |
| `DISABLED_LOCAL_ONLY` | Explicit local/dev/test/docker-local bypass. | Forbidden. |
| `TOKEN_VALIDATOR` | Compatibility shared-token mode for local migration/testing. | Requires opt-in, token hash mode, and an allowlist. |
| `JWT_SERVICE_IDENTITY` | Signed JWT service identity; local demo supports HMAC, RS256 uses key pairs. | `RS256` is a production direction only when keys and environment controls are external; `HS256` is local compatibility only. |
| `MTLS_READY` | Fail-closed compatibility boundary. | Does not trust traffic. |
| `MTLS_SERVICE_IDENTITY` | Certificate-backed service identity. | A production direction only with externally managed certificates and trust policy. |

Unknown modes fail startup instead of downgrading.

## JWT Service Identity

`JWT_SERVICE_IDENTITY` uses short-lived service JWTs. Java clients generate tokens through `InternalServiceAuthHeaders`; business clients do not construct JWTs directly. `ml-inference-service` validates them with PyJWT.

Server-side configuration:

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

`ml-inference-service` enforces key-to-service binding through `INTERNAL_AUTH_ALLOWED_SERVICE_KEYS`. A known key with a mismatched `service_name` is rejected.

## mTLS Service Identity

`MTLS_SERVICE_IDENTITY` requires client certificates for protected ML endpoints. Identity is derived from SAN URI, not CN:

- `spiffe://fraud-platform/fraud-scoring-service`
- `spiffe://fraud-platform/alert-service`

Required server configuration:

- `INTERNAL_AUTH_MODE=MTLS_SERVICE_IDENTITY`
- `INTERNAL_AUTH_MTLS_SERVER_CERTFILE`
- `INTERNAL_AUTH_MTLS_SERVER_KEYFILE`
- `INTERNAL_AUTH_MTLS_CA_FILE` or `INTERNAL_AUTH_MTLS_CA_FILES`
- `INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES=fraud-scoring-service:ml-score,alert-service:governance-read`
- `INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN=fraud-platform`

Required Java client configuration:

- `INTERNAL_AUTH_CLIENT_ENABLED=true`
- `INTERNAL_AUTH_CLIENT_MODE=MTLS_SERVICE_IDENTITY`
- `INTERNAL_AUTH_SERVICE_NAME`
- `INTERNAL_AUTH_MTLS_CLIENT_CERTIFICATE_PATH`
- `INTERNAL_AUTH_MTLS_CLIENT_PRIVATE_KEY_PATH`
- `INTERNAL_AUTH_MTLS_CA_CERTIFICATE_PATHS`
- `INTERNAL_AUTH_MTLS_EXPECTED_SERVER_IDENTITY`
- `INTERNAL_AUTH_MTLS_TRUST_ALL=false`

Trust-all mode is rejected. Java clients keep the platform hostname verifier and do not install a trust-all manager or disable certificate-chain or SAN hostname validation.

## Failure Behavior

- Missing credentials return `401`.
- Expired JWTs return `401`.
- Missing or unknown `kid`, unsupported algorithms, `alg=none`, HS256 in RS256 mode, invalid signature, wrong issuer, wrong audience, unknown service, key/service mismatch, or missing authority return `403`.
- Unknown SAN URI, CN-only certificates, missing certificates, expired/untrusted certificates, and missing endpoint authority are rejected in mTLS mode.
- Auth failures log bounded structured warnings without token contents, certificate serials, SAN values, fingerprints, private material, actor IDs, resource IDs, audit IDs, paths, or exception messages.

## Observability

Metrics must stay low-cardinality:

- `fraud_internal_auth_success_total{source_service,target_service,mode}`
- `fraud_internal_auth_failure_total{target_service,mode,reason}`
- `fraud_internal_auth_replay_rejected_total{reason}`
- `fraud_internal_auth_token_age_seconds{reason}`
- `fraud_internal_mtls_handshake_failures_total{reason}`
- `fraud_internal_mtls_cert_expiry_seconds{source_service,target_service}`
- `fraud_internal_mtls_cert_age_seconds{source_service,target_service}`
- `fraud_internal_mtls_cert_expiry_state_total{state}`

Labels must not include tokens, certificate serials, SAN values, fingerprints, file paths, IPs, users, resources, exception messages, or raw subjects.

## Certificate Lifecycle

Certificates must be rotated before expiration. Runtime lifecycle monitoring updates certificate metrics and emits warnings without blocking request processing. Expired, missing, invalid, or untrusted configured certificates fail startup for configured certificate material.

Manual rotation requires overlap:

1. Generate new server/client certificate and private key from approved CA material.
2. Add new CA or intermediate trust material while keeping old CA trusted.
3. Deploy server trust updates and verify `mtlsCert` health is not `DOWN`.
4. Deploy clients with the new certificate/private key.
5. Verify certificate expiry and age metrics plus `/health` `mtlsCert`.
6. Remove old certificate and old CA trust material only after all clients and servers have moved.

Local fixtures under `deployment/service-identity/` are for development only and must never be used in production.
`deployment/.env` intentionally remains committed as a local runtime fixture. The local application guards reject
its demo token/HMAC/JWT-secret patterns outside local/dev/test/docker-local profiles. Third-party local containers,
including Keycloak dev mode and Grafana, remain local evaluation components and are not a production secret
management mechanism.

## Local Verification

### Compose Overlay Order Matters

Later Compose overlays override earlier values. `deployment/docker-compose.dev.yml` selects
`DISABLED_LOCAL_ONLY` for the local ML path; service-identity overlays must follow it. The recommended security
demonstration therefore resolves to:

- `INTERNAL_AUTH_MODE=MTLS_SERVICE_IDENTITY`
- `ML_MODEL_BASE_URL=https://ml-inference-service:8090`
- `ML_GOVERNANCE_BASE_URL=https://ml-inference-service:8090`
- `APP_SECURITY_DEMO_AUTH_ENABLED=false`
- `APP_SECURITY_BFF_ENABLED=true`
- `AUDIT_TRUST_AUTHORITY_IDENTITY_MODE=jwt-service-identity`

CI executes `scripts/check-compose-security-config.mjs` against rendered Compose JSON so an official command
cannot silently resolve back to local-only internal authentication.

JWT RS256 service identity local demonstration:

```bash
docker compose --env-file deployment/.env -f deployment/docker-compose.yml -f deployment/docker-compose.dev.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-rs256.yml up --build -d
```

mTLS service identity local demonstration:

```bash
docker compose --env-file deployment/.env -f deployment/docker-compose.yml -f deployment/docker-compose.dev.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-mtls.yml up --build -d
```

Recommended local security demonstration with application container hardening:

```bash
docker compose --env-file deployment/.env -f deployment/docker-compose.yml -f deployment/docker-compose.dev.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-mtls.yml -f deployment/docker-compose.trust-authority-jwt.yml -f deployment/docker-compose.hardened.yml up --build -d
```

Expected checks:

- anonymous direct protected ML calls fail
- invalid bearer tokens fail
- HS256 token in RS256 mode fails
- unknown `kid` fails
- wrong audience JWT fails
- key/service mismatch fails
- scoring through `fraud-scoring-service` succeeds
- governance reads through `alert-service` succeed
- wrong service authority is rejected
- auth metrics use bounded labels
- logs do not include token, key, or certificate material

## Limitations

This is an internal service-auth foundation and local security demonstration surface. It is not a production
deployment, enterprise IAM, automated key rotation, automated certificate lifecycle management,
cert-manager/Vault/KMS/HSM integration, full zero-trust certification, WORM storage, SIEM integration, an
independent trust anchor, or a compliance archive.
