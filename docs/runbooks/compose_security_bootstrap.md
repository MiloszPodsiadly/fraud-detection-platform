# Local Compose security bootstrap

## Purpose and scope

This runbook starts the repository's local security-hardened Compose stack and explains how its local key fixtures are
prepared. It is for disposable development data only; it does not provision production secrets or database trust.

## Start and verify

From the repository root, `make up` creates `deployment/.env` from the example when needed, ensures local service-identity,
model-promotion and audit-trust fixtures, generates the local Shadow Performance summary, validates the resolved Compose
configuration with `scripts/check-compose-security-config.mjs security-hardened`, then builds and starts the stack. The
Makefile composes the base, development, OIDC, mTLS service-identity, trust-authority, application-hardening,
model-promotion-trust and generated Shadow Performance overlays in one resolved configuration.

The local Mongo replica set uses a generated keyfile, a root bootstrap account, and separate application accounts.
Governance and trust-authority users are scoped to their own databases. `alert_app` owns the alert database; the
`policy_publisher_app` receives a custom role limited to policy publication jobs, published policy records, their
approval evidence, and publication fences. Redis disables its default account and loads separate ACL users for
`feature-enricher` and `alert-service`.
The committed `.env.example` values are local demonstration credentials only. If `deployment/.env` already exists from an
older checkout, add the new Mongo and Redis values from `.env.example` individually; do not overwrite the existing file.

## Migrate an existing Mongo volume to authentication

Fresh Mongo volumes are initialized with users automatically. An existing volume created by the unauthenticated Compose
configuration needs a one-time user bootstrap before Mongo is recreated with authentication. Keep the old Mongo
container running with its current no-auth configuration. From the repository root, stop the application services while
leaving MongoDB, Redis, and Kafka running:

```bash
docker compose --project-name fraud-detection-platform --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  stop audit-trust-authority ml-inference-service transaction-ingest-service transaction-simulator-service \
  feature-enricher-service fraud-scoring-service alert-service model-promotion-policy-publisher analyst-console-ui
```

Then run the explicit migration service from the new checkout using the same project name and env file:

```bash
docker compose --project-name fraud-detection-platform --env-file deployment/.env \
  -f deployment/docker-compose.yml \
  run --rm --no-deps mongodb-auth-migrate
```

The migration is safe to rerun. On the legacy no-auth server it creates the root account, the scoped application
accounts, and the publisher role. If authentication is already enabled, it authenticates with the configured root
credentials and converges the application users and publisher role without changing stored documents or indexes. Keep
`MONGODB_ROOT_PASSWORD` equal to the current Mongo root password once authentication is enabled: the authenticated
migration path will reject a mismatch and will not rotate the root password. On a legacy no-auth server, the configured
value becomes the root credential used when authentication is enabled. The migration updates application passwords to
the values in `deployment/.env`; restart services after changing them.
An incorrect root password, unsupported authentication state, or interrupted operation fails closed; fix the cause and
rerun the migration before starting applications. After it completes, start the stack using `make up`. This recreates
Mongo with authentication while preserving the named `mongodb-data` volume. If the old Mongo container is already
stopped, first start that existing volume with the previous Compose definition, then run the migration service. Do not
use `make app-clean` for this upgrade; it deletes database volumes.

Use `make check-compose` to validate the resolved configuration without starting containers. `make app-ps` shows service
health; check key-loader and authority logs if the trust services do not become healthy. The one-shot loaders populate
dedicated Docker volumes from ignored `deployment/.local/` fixture paths. The authority and publisher consume separate
read-only key volumes; a missing or partial fixture is a startup error. Do not copy local private keys into Git or a
production deployment.

## Stop and reset

`make down` stops the stack and preserves named volumes. For this early-stage local application, `make app-clean` runs
Compose `down -v` and deletes all named volumes, including MongoDB and Redis data. Use it only when local database state
can be discarded; the next `make up` recreates the databases and fixture volumes. It does not remove ignored host fixture
files under `deployment/.local/`.

## Boundaries

- Do not use local fixtures, Compose networks, shared local Mongo credentials, or demo OIDC settings as production
  trust controls.
- The generated Mongo keyfile is suitable only for the documented single-member local replica set. Multi-member and
  production replica sets need a stable, shared keyfile or another reviewed internal-authentication mechanism and
  production-managed credentials.
- Do not start a key loader by itself with ad hoc mounts; use the repository's resolved overlay stack and guard.
- `make check-compose` proves the configured local source and mount constraints, not a deployed host's filesystem
  permissions or production secret custody.
- For startup failures, preserve the volume state, inspect Compose health and key-loader logs, and resolve the source
  configuration before using `make app-clean`.
