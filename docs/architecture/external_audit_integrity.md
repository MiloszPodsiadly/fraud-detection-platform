# External audit integrity

`ExternalAuditIntegrityService` is the read use-case coordinator and the single owner of sensitive-read audit events.
It parses bounded queries, maps repository/provider failures, records the top-level result and delegates calculation.

`ExternalAnchorIntegrityVerifier` compares the current local anchor with its immutable external witness and preserves
signature/trust policy. `ExternalAnchorCoverageCalculator` computes bounded missing ranges and publication status
aggregation. `ExternalAuditIntegrityResponseFactory` alone assembles the public integrity and coverage responses.

These boundaries do not conflate an immutable anchor object, navigation-manifest health, local publication tracking
or an independent external witness guarantee. Degraded manifest publication remains distinct from invalid anchor
binding, and unavailable stores remain fail closed.

## Canonical signature cutover

Trust Authority signs the canonical `anchor_id`, `chain_position`, `partition_key`, `payload_hash`, `purpose` and
`signed_at` fields. Verification reconstructs exactly that payload; signatures produced before `signed_at` was bound
are incompatible and have no legacy verification fallback.

The development rollout is a clean-environment cutover. Before starting the current runtime, the environment must not
retain old-format signature evidence in the `fraud_alerts.audit_external_anchor_publication_status` collection. The
Compose `mongodb-data` volume persists this collection across container replacement, so replacing containers alone is
not sufficient. `audit_chain_anchors` and `fraud_trust_authority.trust_authority_audit_events` are also persistent audit
evidence and must belong to the same clean development lineage. The default local external-anchor file and Trust
Authority key/audit paths live under container `/tmp` and are not named Compose volumes; a bind mount changes that
assumption and must be checked separately.

Any configured object-store bucket/prefix and any retained evidence export are outside Compose volume ownership. They
must be confirmed empty for this development lineage or handled as a separately authorized migration; clearing Mongo
does not clear them. The repository Compose default does not configure a production object-store adapter, so a fresh
local deployment with a fresh `mongodb-data` volume, fresh containers and no reused external sink or bind mount satisfies
the cutover precondition. This statement establishes support for that clean deployment path; it does not assert that an
arbitrary existing environment is empty.
