# Security Documentation

This directory contains the current security source of truth for the platform runtime.

| Document | Purpose |
| --- | --- |
| [Security architecture](security_architecture.md) | Human, browser, service-to-service, audit, and operational security model. |
| [Endpoint authorization map](endpoint_authorization_map.md) | Route ownership, matcher ordering, public endpoint rationale, and maintainer checklist for backend endpoints. |
| [Internal service identity](internal_service_identity.md) | JWT and mTLS contracts for internal calls into `ml-inference-service`. |

Historical FDP branch notes stay under `docs/fdp/`. Security implementation docs in this directory describe the current codebase, not branch history.
