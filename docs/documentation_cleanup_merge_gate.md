# Documentation Cleanup Merge Gate

Status: current documentation cleanup merge gate.

## Scope

This gate applies only to documentation honesty, readability, naming, link integrity, and documentation safety
tests. It is not a runtime release gate and does not approve production enablement.

## Required Evidence

- Documentation inventory exists: `docs/documentation_inventory.md` and `docs/documentation_inventory.json`.
- Documentation audit exists: `docs/documentation_audit.md`.
- Documentation style guide exists: `docs/documentation_style_guide.md`.
- Documentation naming map exists: `docs/documentation_naming_map.md`.
- Root README remains the repository overview.
- Docs index exists: `docs/index.md`.
- Current architecture summary exists: `docs/architecture/current_architecture.md`.
- OpenAPI safety audit exists: `docs/api/openapi_safety_audit.md`.
- Public API semantics are documented: `docs/api/public_api_semantics.md`.
- Status truth table exists: `docs/api/status_truth_table.md`.
- Configuration guide exists: `docs/configuration/configuration_guide.md`.
- Architecture diagrams exist: `docs/architecture/diagrams.md`.
- Runbook requirements are documented or represented in professional runbooks.
- FDP branch evidence remains traceable and is not rewritten as current production proof.
- `DocumentationReadabilityTest` is green.
- `HiddenUnicodeDocumentationSafetyTest` is green.
- `NoFakeClaimsDocumentationTest` is green.
- `DocumentationLinkIntegrityTest` is green.
- `PublicApiDocumentationConsistencyTest` is green.
- `ConfigurationDocumentationSafetyTest` is green.
- `DocumentationInventoryHonestyTest` is green.
- CI docs path alignment is green.
- Application runtime code is unchanged.

## Non-Claims

This documentation cleanup is an honesty and readability scope. It does not claim production enablement, bank
certification, external finality, WORM storage, distributed ACID, exactly-once Kafka, or legal notarization.

## GO / NO-GO

GO requires all required evidence above and no application runtime code changes.

NO-GO if docs:

- leave current docs as one-line blobs;
- contain hidden, bidi, zero-width, non-breaking, or unsupported control characters;
- imply future behavior as current behavior;
- hide limitations;
- expose sensitive example values;
- claim production enablement;
- claim bank certification;
- claim external finality;
- claim distributed ACID;
- claim exactly-once Kafka;
- describe fixture proof as production proof;
- confuse readiness with enablement;
- present FDP branch evidence as current truth without context;
- contain hidden Unicode or bidi control characters;
- fail raw Markdown auditability checks.

NO-GO if this documentation cleanup changes application runtime files, production config semantics, public
DTOs/controllers/enums, transaction configuration, Kafka/outbox behavior, or mutation executors/coordinators/handlers.
