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
- Do not start a key loader by itself with ad hoc mounts; use the repository's resolved overlay stack and guard.
- `make check-compose` proves the configured local source and mount constraints, not a deployed host's filesystem
  permissions or production secret custody.
- For startup failures, preserve the volume state, inspect Compose health and key-loader logs, and resolve the source
  configuration before using `make app-clean`.
