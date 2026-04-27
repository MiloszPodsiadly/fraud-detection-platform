# FDP-18 mTLS Service Identity

FDP-18 adds certificate-backed service identity for internal calls into `ml-inference-service`.

## Scope

mTLS applies only to internal service-to-service traffic:

- `fraud-scoring-service` -> `ml-inference-service` scoring with `ml-score`
- `alert-service` -> `ml-inference-service` governance reads with `governance-read`

Browser/OIDC traffic is separate and does not use mTLS. FDP-18 does not change scoring behavior, ML model behavior, Kafka contracts, governance workflow, retraining, rollback, alert triggering, or SLA enforcement.

## Mode

`INTERNAL_AUTH_MODE=MTLS_SERVICE_IDENTITY` enables mTLS service identity on `ml-inference-service`.

Protected scoring and governance endpoints require a client certificate in this mode. `/health` remains public for runtime health checks. Metrics may remain public in local Docker and must be protected by deployment controls in production.

The previous `MTLS_READY` mode remains fail-closed and is not a trust-all mode.

## Identity Contract

Client identity is derived from SAN URI, not CN:

- `spiffe://fraud-platform/fraud-scoring-service`
- `spiffe://fraud-platform/alert-service`

Allowed service authorities:

- `fraud-scoring-service`: `ml-score`
- `alert-service`: `governance-read`

Unknown SAN URI, CN-only certificates, missing certificates, expired/untrusted certificates, and missing endpoint authority are rejected. Header-based identities and JWT bearer tokens are ignored in `MTLS_SERVICE_IDENTITY` mode.

## Server Configuration

`ml-inference-service` requires:

- `INTERNAL_AUTH_MODE=MTLS_SERVICE_IDENTITY`
- `INTERNAL_AUTH_MTLS_SERVER_CERTFILE`
- `INTERNAL_AUTH_MTLS_SERVER_KEYFILE`
- `INTERNAL_AUTH_MTLS_CA_FILE` or `INTERNAL_AUTH_MTLS_CA_FILES`
- `INTERNAL_AUTH_ALLOWED_SERVICE_AUTHORITIES=fraud-scoring-service:ml-score,alert-service:governance-read`
- `INTERNAL_AUTH_MTLS_SPIFFE_TRUST_DOMAIN=fraud-platform`

Startup fails closed if required material or service authority configuration is missing.

## Java Client Configuration

Java clients use one shared HTTP client boundary. Business clients do not manually attach mTLS headers or certificates.

Required client configuration:

- `INTERNAL_AUTH_CLIENT_ENABLED=true`
- `INTERNAL_AUTH_CLIENT_MODE=MTLS_SERVICE_IDENTITY`
- `INTERNAL_AUTH_SERVICE_NAME`
- `INTERNAL_AUTH_MTLS_CLIENT_CERTIFICATE_PATH`
- `INTERNAL_AUTH_MTLS_CLIENT_PRIVATE_KEY_PATH`
- `INTERNAL_AUTH_MTLS_CA_CERTIFICATE_PATHS`
- `INTERNAL_AUTH_MTLS_EXPECTED_SERVER_IDENTITY`
- `INTERNAL_AUTH_MTLS_TRUST_ALL=false`

Trust-all mode is rejected. Server certificate validation uses configured CA material and the target host must match the expected server identity.

## Observability

Metrics are low-cardinality:

- `fraud_internal_auth_success_total{source_service,target_service,mode}`
- `fraud_internal_auth_failure_total{target_service,mode,reason}`
- `fraud_internal_mtls_certificate_expiry_seconds{source_service,target_service}`

Labels do not include token values, certificate serials, SAN values, fingerprints, paths, IPs, users, resources, exception messages, or raw subjects.

## Local Docker Fixture

Local-only fixtures live under `deployment/service-identity/mtls/`. They are for local development and verification only and must never be used in production or shared environments.

Regenerate them with:

```powershell
python deployment\service-identity\mtls\generate-local-mtls-certs.py
```

Run the mTLS local stack with:

```bash
docker compose -f deployment/docker-compose.yml -f deployment/docker-compose.oidc.yml -f deployment/docker-compose.service-identity-mtls.yml up --build -d
```

Expected checks:

- direct protected ML calls without a client certificate fail
- scoring through `fraud-scoring-service` succeeds
- governance reads through `alert-service` succeed
- alert-service certificate cannot call scoring
- fraud-scoring-service certificate cannot call governance
- unknown service certificate is rejected

## Rotation Readiness

Static CA/certificate files can be configured with overlapping material during manual rotation. FDP-18 does not implement automated rotation, cert-manager, Vault, KMS/HSM integration, external PKI automation, or dynamic certificate reload.

## Limitations

FDP-18 is an internal mTLS service identity foundation. It is not enterprise IAM, not full zero-trust certification, not automated certificate lifecycle management, not WORM storage, not SIEM integration, and not a compliance archive.
