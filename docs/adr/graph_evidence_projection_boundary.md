# Graph evidence projection boundary

**Status:** disabled, test-only. There is no production Graph grant issuer, Spring bean, API, Kafka consumer, writer/reader registration, or scorer registration. Local test results do not authorize production use.

## Purpose and claim

The candidate represents only a retrospective association between an exact transaction occurrence and a scoped Device pseudonym that was also referenced by a strictly earlier occurrence. It does not establish customer identity, physical-device identity, fraud, risk level, scoring-time knowledge, or complete absence of matching history.

An empty Graph query is `UNAVAILABLE/COVERAGE_UNAVAILABLE`, not verified absence. Missing, stale, denied, revoked, conflicting, expired, truncated, changed, or timed-out evidence returns a typed non-positive result and no partial witness.

## Authority flow

1. The Source owner verifies the signed transaction, retained receipt, and exact RAW/ENRICHED commitments. A scored occurrence is looked up only through verified lineage and its configured purpose and audience; guessed IDs or caller-supplied commitments are not authority.
2. The Device owner verifies its separate signed observation, coverage generation and continuity, current signer policy, pseudonym key version, and the exact persisted `INITIAL_RUN` score. It resolves the owner evidence again around the Graph read.
3. Graph admission accepts an opaque owner reference, principal, and short deadline. It checks Graph purpose and scope before and after owner resolution, then persists only an admission-owned handle.
4. The private Graph reader binds each request to an exact occurrence root, full scope, private grant, and bounded time window. It reads a bounded candidate set, checks owner authority, compares ordered Graph snapshots around those checks, and rechecks the Graph grant before returning.
5. The package-private scorer candidate binds evidence to the exact scoring context and requires a fresh typed result for occurrence revalidation. Its positive result is diagnostic only; no production adapter is configured.

The signed scorer E2E keeps Source, Device, Kafka, and Mongo fixtures alive across both evaluations and the intervening signed conflict. It is test-only and does not create a production grant issuer or provider. Exact test and build evidence is in the [candidate ADR](graph_risk_diagnostic_candidate.md).

## Stored evidence and Mongo access

Graph rows contain the full opaque scope, typed occurrence and Device references, key version, commitments and owner references, exact score reference, bounded times, and lifecycle. They do not contain raw customer/device IDs, transaction payloads, assertion bytes, signatures, or grant secrets.

The versioned schema is strict. The schema owner, writer, and reader use distinct Mongo identities. Runtime readers are read-only, strictly decode each row, and use the `graph_device_neighbors` index for reverse Device lookups. The query binds full scope, edge type, Device token and key version, lifecycle, and event-time bounds; it applies the corresponding stable sort, page size, `maxTime`, and `maxEdges + 1` overflow sentinel. Derived Device hub sets are split into chunks of at most 16 under one remaining-time budget and one global result bound. Overflow returns `TRUNCATED_UNVERIFIED` with no witness. Schema changes require a separately reviewed migration; startup does not repair or migrate stored data.

## Limits and deadline behavior

| Limit | Maximum |
|---|---:|
| Traversal depth | 2 |
| Nodes / edges | 128 / 256 |
| History rows | 1,000 |
| Page size | 100 |
| Event-time window | 30 days |
| Owner request deadline | 5 seconds |

One monotonic budget is propagated through Device, Source lineage, and score-owner reads. Transactional Mongo reads use the remaining timeout at transaction start and check the same budget between operations; the Mongo driver does not allow changing `timeoutMS` part-way through a transaction. Nontransactional occurrence lookups use the remaining client-side operation timeout.

The caller has a deadline-enforcing wait on a bounded owner-read pool capped at eight workers, backed by a synchronous handoff with no task queue. A timed-out request returns `DEADLINE_EXCEEDED` and discards any late result. The underlying synchronous Mongo operation may continue occupying one bounded worker until the driver or server releases it; cancellation is not proof of immediate server-side cancellation. The caller cutoff is not a hard real-time guarantee under arbitrary scheduler or network failure. Injected 8-second Mongo stalls returned to the caller after approximately five seconds at the Device, Source, and initial-score stages; detailed timings and commands are in the candidate ADR.

## Remaining boundaries

Source, Device, Graph, and scorer stores do not share one atomic transaction. A revocation after the final owner check can race with a read result; the candidate is retrospective and must not be cached or promoted to a scoring-time fact. Owner outages and timeouts do not mutate durable lifecycle state. There is no TTL deletion, compaction, background recovery worker, or approved retention policy. Merchant Graph relations remain unavailable until the Merchant owner issues a scoped per-occurrence capability.
