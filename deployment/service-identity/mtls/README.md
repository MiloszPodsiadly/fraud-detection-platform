## Local mTLS Fixtures

These certificate fixtures are for local Docker development and verification only.

They must NEVER be used in production or shared environments. Production deployments must use externally managed CA, server certificate, client certificate, and private-key material.

Regenerate the local fixtures with:

```powershell
python deployment\service-identity\mtls\generate-local-mtls-certs.py
```

Identity mapping uses SAN URI values:

- `spiffe://fraud-platform/fraud-scoring-service`
- `spiffe://fraud-platform/alert-service`

CN-only identity is intentionally not accepted by FDP-18 mTLS service identity.
