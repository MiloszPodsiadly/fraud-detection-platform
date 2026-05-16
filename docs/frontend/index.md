# Frontend Documentation Index

Status: current frontend documentation index.

## Scope

This folder contains current Analyst Console frontend architecture guidance. FDP-numbered frontend docs remain under
[FDP branch evidence](../fdp/index.md) unless they are promoted into current source-of-truth docs here.

## Current Sources

| Document | Scope |
| --- | --- |
| [Frontend API client boundary](api_client_boundary.md) | Auth-sensitive API client construction, allowed frontend API access patterns, and test guard expectations. |

## Related Documents

- [FDP-50 frontend API client boundary](../fdp/fdp_50_frontend_api_client_boundary.md)
- [Security documentation](../security/index.md)
- [API documentation](../api/index.md)
- [CI evidence map](../ci_evidence_map.md)

## Interpretation Rules

- Frontend guardrails do not replace backend authorization.
- UI code must not persist bearer tokens, CSRF tokens, opaque cursors, or session tokens in browser storage.
- API routes belong behind explicit API client boundaries.
- Product workflow semantics belong in product/API docs, not frontend boundary docs.
