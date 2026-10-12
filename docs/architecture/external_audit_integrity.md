# External audit integrity

`ExternalAuditIntegrityService` is the read use-case coordinator and the single owner of sensitive-read audit events.
It parses bounded queries, maps repository/provider failures, records the top-level result and delegates calculation.

`ExternalAnchorIntegrityVerifier` compares the current local anchor with its immutable external witness and preserves
signature/trust policy. `ExternalAnchorCoverageCalculator` computes bounded missing ranges and publication status
aggregation. `ExternalAuditIntegrityResponseFactory` alone assembles the public integrity and coverage responses.

These boundaries do not conflate an immutable anchor object, navigation-manifest health, local publication tracking
or an independent external witness guarantee. Degraded manifest publication remains distinct from invalid anchor
binding, and unavailable stores remain fail closed.
