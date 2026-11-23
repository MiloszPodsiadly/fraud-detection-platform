# Graph evidence projection boundary

Status snapshot (2026-11-23): G02-A/G02-C, G03-A/B/C, G04-A/B, G05-A/B/C, G06, and G07 are complete inside the disabled, test-only scope. FDP-156's FX-01–FX-08 repairs and current local evidence are recorded in the [Graph risk diagnostic candidate ADR](graph_risk_diagnostic_candidate.md). G02-B Merchant remains OPTIONAL BLOCKED. Production projection remains NO-GO; this snapshot has no exact-head CI result.

## Context

The repository has private, owner-specific Device and Merchant evidence paths. This proposal evaluates whether either path can support a bounded relation projection without turning a pseudonym, digest, source receipt, diagnostic claim, or guessed scored ID into authority.

The current source occurrence boundary keeps source authenticity separate from a scored fact reference. Source lineage is private processing context, and only its verifier can create a VERIFIED lineage (SourceLineage.java:9-25, :38-50). Exact scored lookup requires verified source lineage, the owner-configured purpose and audience, exact event ID/time/fingerprint, receipt, and ENRICHED commitment (SourceOccurrenceAccess.java:7-20, MongoSourceOccurrenceStore.java:303-329).

## Decision proposal

G00-A finds one narrow candidate for further gated design: an occurrence-to-Device association within the same issuer, institution, source, source epoch, Device identityVersion, population, and Device pseudonym key version. The Device owner verifies a separate signed observation against the actual source receipt and RAW/ENRICHED commitments, a signed coverage generation, and an exact persisted initial scored occurrence (VerifiedDeviceAdmission.java:64-84, :96-129; DeviceEpoch.java:21-41). G05-A now exercises two distinct, signed source/Device occurrences that resolve to one permitted Device pseudonym, persist as two Graph rows, replay idempotently, and are read after Graph/source/Device adapter reconstruction (`PrivateDeviceDiagnosticReaderIntegrationTest.deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart`).

Existing Device signer grants still authorize only the Device observation and coverage purposes for their private Device audience. G02-A adds explicit test-only Graph relationship and read grants, plus a Device-owned adapter that re-resolves source, Device, coverage, and exact score evidence after a new adapter is constructed. These fixtures do not authorize production projection: there is no production Graph grant issuer or verifier implementation, Spring registration, projection writer, or scoring path. Runtime remains absent and default-deny; G04-A's package-private reader cannot be constructed from production configuration.

The optional Merchant edge is blocked. Merchant has a source-bound, independently signed per-occurrence assertion and retains its signed bytes for restart validation (MerchantAssertionVerifier.java:54-110, MerchantAssertionAnchor.java:9-39). Its private reader returns a complete-window diagnostic claim, not a Graph relation capability; the trust policy exposes only observation and coverage signer roles, and neither those purposes nor the diagnostic reader authorize Graph use (MerchantAssertionVerifier.java:23-24, :331-337; PrivateMerchantDiagnosticReader.java:36-42, :150-158, :222-244). Equal merchant pseudonyms do not establish a cross-source or cross-epoch mapping. G02-B is `OPTIONAL BLOCKED` (`MERCHANT_GRAPH_RELATION_NOT_AUTHORIZED`): the owner has no Merchant `GRAPH_RELATION_PROJECTION` grant, no Merchant-native relation resolver, and no authorized Merchant path through the generic private Graph reader. Merchant node/edge types remain absent from the Graph domain model; negative tests preserve that denial. Any future institution-wide mapping requires its own versioned owner grant and review.

## Trust boundary and proposed flow

    Source owner
      verifies signed original transaction and retained RAW / ENRICHED lineage
           |
           v
    Device owner
      verifies independent Device assertion, signed coverage and current policy
      resolves a narrow persisted occurrence-to-device reference
           |
           v
    Graph admission (disabled; no production bean)
      checks separate Graph relation grant, source INITIAL_RUN binding,
      owner-resolved current authority and same-scope policy
           |
           v
    Private projection store / bounded private reader (future gated work only)

The Graph layer must not copy Device or Merchant BSON, source payloads, raw identifiers, signer keys, assertion bytes, or broad owner-store privileges. It must persist only typed references that the domain owner can resolve again after restart. An opaque Java value is not a security boundary; the owner must re-read authoritative state before admission and before a private read.

## Ownership and dependency table

| Trust boundary | Authoritative owner | Minimum data crossing it | Required revalidation | Current evidence |
|---|---|---|---|---|
| Source occurrence | Source lineage and occurrence store | Exact source scope, receipt, RAW/ENRICHED commitments, event/time/fingerprint reference | Verify current source receipt and exact occurrence; require INITIAL_RUN | MongoSourceOccurrenceStore.java:303-329; Device and Merchant admission checks |
| Device relation | Device assertion verifier, observation store, and private `DeviceGraphRelationAuthority` | Typed exact occurrence reference, scoped Device pseudonym/key version, opaque owner/coverage/policy references, exact INITIAL_RUN score reference | Resolve the source owner binding and score, recheck current Device and coverage grants, integrity anchors, continuity, retention and revocation; repeat source/score resolution after a second Device snapshot | DeviceGraphRelationAuthority.java; MongoDeviceObservationStore.java; VerifiedDeviceAdmission.java:119-141; DeviceHistoryMemberAnchor.java:10-38 |
| Merchant relation | Merchant assertion verifier and observation store | Not authorized for Graph in this proposal | Would need current signed observation, mapping, population/coverage, exact initial score, row retention and revocation checks under a separate Graph purpose | VerifiedMerchantAdmission.java:157-215; MerchantAssertionVerifier.java:157-201 |
| Graph read | Test-only `GraphPrivateReadGrant` and separate `GraphPrivateReadGrantVerifier`; no production issuer | Exact scope, typed occurrence root, bounded time range, and requesting principal | Private reader revalidates every stored relation twice, compares bounded Graph snapshots, and rechecks the Graph grant after the final read; cross-store atomicity is not available | GraphPrivateReadGrant.java; GraphProjectionReader.java; DeviceGraphRelationAuthority.java; reader remains package-private and unwired |

## G00-B architecture decisions

| Decision | Proposed boundary |
|---|---|
| Module | Keep the future implementation inside feature-enricher-service under a private graph package. Use pure immutable domain types; keep Mongo and framework adapters at the edges. Do not add a service or Neo4j. |
| Owner relation port | GraphRelationAuthority returns an owner-issued, resolvable reference for the narrow Device relation. Device remains package owner and rechecks its persisted state. Graph does not construct a trusted ticket from caller values. |
| Source occurrence port | GraphSourceOccurrenceVerifier resolves source lineage and the exact persisted INITIAL_RUN scored occurrence. A guessed event ID, receipt, or digest is insufficient. |
| Admission | GraphEvidenceAdmission checks the source occurrence, resolved owner reference, current Graph-purpose grant, scope compatibility, and immutable GraphPolicyLimits before writing. The owner reference is resolved again at read time. |
| Persistence and reads | GraphProjectionStore owns only Graph projection data. GraphProjectionReader accepts a bounded query only after GraphReadGrantVerifier confirms principal, purpose, audience, scope, expiry, and permitted fields. It never queries Device or Merchant collections directly. |
| Runtime activation | No Spring registration, writer, event, endpoint, or scoring path. Production remains disabled. A test-only grant and adapter are allowed only in test scope and cannot be loaded from production configuration. |
| Query semantics | Provide bounded direct neighbors; any two-hop shared-Device query needs both occurrence proofs independently verified. Do not add generic graph traversal or cross-scope unions. Never return a risk score or treat incomplete coverage as an empty result. |
| Mongo | A future Graph collection set must be private and separately permissioned, versioned, strict, and bootstrapped only against an empty database. No destructive migration, automatic repair, or direct Device/Merchant database permissions. |

The minimum boundary is therefore:

    GraphEvidenceAdmission
      -> GraphSourceOccurrenceVerifier -> source occurrence owner
      -> GraphRelationAuthority       -> Device owner
      -> GraphReadGrantVerifier       -> server-owned Graph read policy
      -> GraphProjectionStore         -> private Graph projection rows

The public Java interface is not itself authority. Before each write and bounded read, admission resolves the typed owner reference against current source and Device state, checks current grants and retention, and fails closed if any owner is unavailable. Cross-store operations are not atomic; the projection is an idempotent, replay-safe derived view and must never claim current completeness beyond the owner response.

### G00: authorized two-hop witness (design only)

**Decision: GO to G01 only inside the disabled, test-only candidate boundary.** Existing owner APIs can re-resolve each stored `GraphEvidenceRef` against the exact source receipt and RAW/ENRICHED binding, current signed Device observation and coverage, and the exact persisted `INITIAL_RUN` score. The only current Graph grant factory and Device Graph owner adapter are test-only; this decision does not authorize a production grant issuer, runtime provider, or scorer-to-Graph persistence dependency.

The new Reader operation must accept an exact occurrence root B and purpose-bound Graph read grant; it must not accept a Device hub. It first loads B's bounded, full-scope occurrence edges and revalidates them through the Device owner. It derives D only from the owner-resolved evidence on a B edge. A narrow DAO method then loads D's occurrence neighbors with equality on every scope field, the Device pseudonym and key version, the event-time range `[B.eventTime - 30 days, B.eventTime)`, `ACTIVE`, `admittedAt <= diagnosticReadAt`, and `validUntil > diagnosticReadAt`. It uses a deterministic event-time/token order, an overflow sentinel, and Mongo `maxTime` from the remaining request budget. Each candidate A is independently revalidated by the owner; only a distinct canonical occurrence with `A.eventTime < B.eventTime` can form a redacted witness. No match, incomplete scan, owner uncertainty, or expired/revoked grant never means verified absence.

Use one self-contained bounded read, not an externally paginated two-hop API. Fence both candidate sets: read root and selected-hub sets; revalidate all included owner evidence; reread and compare both sets; revalidate owner evidence again; reread and compare both sets once more; check the Graph grant before the first query, between root and hub phases, and immediately before return. Apply the existing node/edge ceilings across the combined operation, including its overflow sentinels. Any changed set or exhausted budget returns a typed nonverified outcome. G01 passes the remaining operation budget through a dedicated owner-authority overload; its default returns `NOT_SUPPORTED`, so adapters that do not explicitly implement the budget-aware path fail closed. Reader tests verify propagation, but the current signed Device test bridge has not yet implemented that overload. G06 must add and exercise a bounded signed bridge before a positive signed end-to-end result is possible. Repeated checks reduce TOCTOU risk but do not create an atomic cross-store snapshot.

The existing `graph_device_neighbors` index has the required full-scope, edge-type, device-token/key-version equality prefix followed by event time and occurrence token, so it can support an indexed bounded scan and deterministic ordering. Lifecycle predicates are not in the index, and the Reader needs full evidence/lifecycle fields, so the query cannot be covered by this immutable index; Mongo must fetch documents and enforce `maxTime`. G07 must inspect a genuine explain plan and fail closed on an unindexed or over-budget query. No schema or index change is approved by this design.

This result means only “a separately owner-verified earlier occurrence and root occurrence were observed with the same currently authorized scoped Device pseudonym.” The witness keeps occurrence event times, the later of the two Graph edge `admittedAt` values, and the current `diagnosticReadAt` separate. That is a retrospective observation; it does not establish what was known at original scoring time, two customers, fraud, or complete Graph coverage. G01's focused plan is to cover strict time/scope/key isolation, forged hubs, owner/grant changes, each candidate-set fence, overflow and deadline failure in Reader tests; verify the actual indexed query and read-only role in Mongo integration; and extend the existing signed Mongo/Kafka Device vertical to prove one B-rooted two-hop witness plus revocation/conflict disappearance. No two-hop implementation or positive proof exists at this G00 checkpoint.

## Threat model

| Threat | Required behavior |
|---|---|
| TOCTOU between source, Device, Graph, and read-grant stores | Re-resolve current owner and Graph grant before write/read. If state changes during the operation or a final recheck fails, return unavailable and do not emit a verified result. |
| Phantom completeness or omitted occurrence | Require owner-signed coverage and sequence continuity for the exact scope/window. No coverage proof means unavailable, never verified-empty. |
| Key or identity rotation | Include all source identity fields and Device identity/key versions in edge and node identity. A new version starts a separate scope; do not merge prior versions. |
| Lost Mongo acknowledgement or replay | Use deterministic canonical identity and idempotent upsert. Conflicting evidence for the same logical relation becomes conflict/unavailable, not overwrite. |
| Mongo tampering or unknown schema | Verify owner-resolved evidence and Graph row integrity at read; use strict versioned validators and exact index checks. Refuse startup on incompatible schema rather than repairing it. |
| Revoked source, Device signer, coverage, or Graph reader grant | Recheck current authority at write/read and fail closed. TTL deletion lag does not extend logical retention. |
| Restart or adapter replacement | Resolve owner references from durable owner state after restart. Never rely on an in-memory verified object or process cache as proof. |
| Cross-source pseudonym collision or caller-constructed identity | Enforce complete typed identity scope and owner proof. Text equality, hash equality, and caller-supplied claims cannot authorize a join. |

## Query and identity rules

- The only initial candidate is a bounded same-source Device relation. Every occurrence needs its own source proof, Device assertion, coverage membership, and exact initial scored binding.
- Device pseudonyms are HMAC values derived from institution and raw device ID; they are not source-scoped by themselves (DevicePseudonyms.java:26-29, :42-49). A Graph node key must therefore include source issuer, institution, source, epoch, Device identityVersion, pseudonym key version, and edge/evidence identity. Never join on the Device token alone.
- Merchant pseudonyms likewise include institution and merchant ID with identity and mapping versions, while the owner store scope separately includes issuer, institution, source, epoch, and identity version (MerchantPseudonyms.java:45-70, MerchantObservationStore.java:51-57). No cross-source join is admitted here.
- No SAME_PERSON, account, network, ring, fraud score, or risk-level semantics are inferred.
- Missing coverage, missing owner rows, a rescored transaction, invalid current grants, expired retention, policy changes, or any mismatched scope return unavailable/denied; they do not mean an empty graph.

## Gates and non-goals

1. G02-A code/test gate: a private typed Device relation capability is issued only through the test-only owner adapter and can be re-resolved from durable owner state. The test uses real source/Device test signatures, Mongo auth, and exact published INITIAL_RUN score. The adapter revalidates before returning both an initial capability and a later read capability. No Graph engine is registered in scoring.
2. Remaining Graph gate: GraphEvidenceAdmission, source/owner authorization composition, persistence and bounded reads are not implemented. The owner bridge's `Optional` result is a test-only capability seam, not a production decision API.
3. Production and institution-onboarding gate: requires separate owner approval, actual purpose-bound grants, authenticated Graph reader service, operational credentials, and independent review. No production authorization follows from this audit.

Explicit non-goals: GraphRiskSignalEngine, graph.primary, EI v3, public Graph API/UI, scoring or alert effects, Kafka topic, GraphQL/REST/frontend, new service, Neo4j, production runtime wiring, and destructive data migration.

G02-A demonstrates owner-side revalidation after rebuilding the adapter: it reads only six Device collections through a Mongo principal restricted to `find` plus database `listCollections`; the source and exact score are resolved through their existing owner APIs. Device rows are authenticated by the existing application HMAC integrity anchors written only after signed admission, and current signer/coverage policy is checked again. Signed assertion bytes are not retained or copied to Graph. A tampered owner row, lost integrity key, missing owner row, revoked grant, expired record, or unavailable owner returns no capability. Its focused Maven batch passed 75 tests with 0 failures/errors, including 57 Device assertion tests, six Device admission regressions, two private Device reader integration methods, and ten Graph model tests.

G02-C adds a package-private `GraphEvidenceAdmission`. Its request carries only the opaque owner occurrence reference, authenticated requesting principal, and a deadline no more than five seconds away; it cannot carry a Device token, Graph scope, score reference, or caller-built `GraphEvidenceRef`. `GraphRelationAuthority` resolves the exact source receipt/RAW/ENRICHED binding, Device signed assertion/coverage state, current owner policy and exact initial score. Source and score checks stay composed inside the Device owner because Graph receives only opaque scope tokens. `GraphPolicyAuthorizer` checks the fixed Graph relation purpose before owner I/O, against the owner-resolved scope, and again after final owner revalidation. The final reference must equal the first, deadlines are bounded, and batch size is capped at 256. Only this admission class can construct the internal admitted relation that a future Graph store may accept.

The G02-C focused Maven batch passed 32 tests with 0 failures/errors (eight admission tests, eleven graph-model tests, twelve Merchant verifier tests, and one Testcontainers Device integration method). The integration signs two separate source events and Device assertions for the same permitted pseudonym, publishes and resolves each exact INITIAL_RUN score, admits two distinct edge IDs through the test-only admission facade, and resolves both after building a fresh owner reader. The test-only fixture and facade are under `src/test`; no production Graph authority or policy issuer is registered.

This admission path does not create a global transaction across source, Device, Graph policy, and the Graph database. A grant may be revoked after the last owner check; Graph persistence re-resolves current authority immediately before its local write, and private reads must re-resolve it again. The admission result is a bounded test-only seam, not a serializable or production authorization claim.

## G03-A schema decision

The first projection schema uses one isolated collection, `graph_edges`. Each document stores the strict typed scope, Device occurrence edge, opaque evidence references/commitments, exact INITIAL_RUN reference, and lifecycle (`ACTIVE`, `CONFLICT`, or `REVOKED`, `admittedAt`, `validUntil`). Signed payload bytes, raw identifiers, current grants, and source/Device copies are never stored. Scope fields are persisted separately and participate in all identity/query indexes; a digest is not the only tenant boundary.

The unique logical relation index is `graph_logical_relation_unique` on `scope.sourceIssuerToken`, `scope.institutionToken`, `scope.sourceToken`, `scope.sourceEpochToken`, `scope.identityVersion`, `edgeType`, `occurrence.token`, `device.token`, and `device.keyVersion`. It prevents a second proof for the same scoped occurrence-to-device relation; G03-B compares immutable evidence and records a terminal lifecycle conflict if the proof differs. `graph_device_neighbors` supports reverse bounded lookup by the same full scope, edge type, device token/key version, then occurrence time/token. `_id_` remains Mongo's unique canonical proof-specific edge ID.

At the original G03-A checkpoint, schema v1 had strict validators with `additionalProperties: false` at root and nested evidence/lifecycle objects, explicit required fields/types, and no TTL index. FX02 below supersedes that initial schema with v2. The private reader filters inactive/expired rows, re-resolves every included relation through the Device owner, and compares exact Graph row snapshots before returning. No TTL horizon or compaction policy is approved; database cleanup cannot be an authority decision.

`graph_schema_owner` may create the collection and reviewed indexes only against an empty dedicated Graph database in an offline/test bootstrap. `graph_projection_writer` receives `find`, `insert`, and `update` on `graph_edges` plus metadata inspection required for schema checks; `graph_private_reader` receives `find` and metadata inspection only. Neither principal receives delete or access to source, Device, or Merchant databases. Runtime startup only validates schema/index definitions and fails closed; it never creates, alters, drops, or repairs them.

The schema owner role is limited to database `listCollections`/`createCollection` and collection `find`/`listIndexes`/`createIndex`. The writer has database `listCollections` and collection `find`/`listIndexes`/`insert`/`update`; the reader has database `listCollections` and collection `find`/`listIndexes`. Both runtime adapters use primary reads with `ReadConcern.MAJORITY`. Delete, index creation, and source/device/merchant database reads are denied by Mongo itself. The writer's update privilege is used only for lifecycle conflict/revocation fences; G03-B tests confirm immutable proof fields are unchanged.

The original v1 BSON document had a `coverageWatermark` field in `evidence`. It is historical only: current schema v2 omits that mutable watermark from immutable proof identity and stores the historical commitment in the signed per-occurrence anchor. Both versions used strict required nested objects; v1 rows now fail closed and are not auto-migrated or reinterpreted.

Reviewed indexes (key order is part of the startup contract):

- `_id_`: Mongo's built-in unique canonical edge ID.
- `graph_logical_relation_unique` (unique): `{scope.sourceIssuerToken: 1, scope.institutionToken: 1, scope.sourceToken: 1, scope.sourceEpochToken: 1, scope.identityVersion: 1, edgeType: 1, occurrence.token: 1, device.token: 1, device.keyVersion: 1}`. The full scope and identity fields prevent tenant/epoch collisions. The evidence reference is deliberately not part of this logical key, so a second proof cannot coexist as another row; G03-B handles a duplicate with changed evidence as a terminal conflict.
- `graph_device_neighbors`: `{scope.sourceIssuerToken: 1, scope.institutionToken: 1, scope.sourceToken: 1, scope.sourceEpochToken: 1, scope.identityVersion: 1, edgeType: 1, device.token: 1, device.keyVersion: 1, occurrence.eventTime: 1, occurrence.token: 1}` for scoped reverse-neighbor reads in stable order.

The focused G03-A run passed 11 tests (nine authenticated Mongo Testcontainers integration tests and two codec/schema unit tests) with 0 failures/errors. Mongo `7.0` ran as an authenticated single-node replica set on Windows 11, Java 21, Docker Desktop 29.5.2. The explain checks found `graph_logical_relation_unique` for exact logical lookup and `graph_device_neighbors` for reverse neighbors; the Mongo adapter returned the exact logical edge and bounded neighbor result. The tests also verified valid v1 inserts, rejection of unknown root/evidence fields and schema versions, tenant/epoch isolation, unique-fence rejection of changed proof, role denials, empty-database bootstrap, and unchanged data/indexes/documents after failed startup on missing/wrong indexes or validator drift. Command:

```text
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=GraphProjectionSchemaTest,GraphProjectionSchemaIntegrationTest" test
```

At the G03-A checkpoint compatibility was strict to schema v1. FX02 upgraded the current schema and canonical key version to v2; v1 rows fail closed. Startup refuses missing, extra, or differently defined collections/indexes. There is no in-place upgrade, automatic `collMod`, index repair, or reset. A future schema change needs a separately reviewed offline migration and a compatibility decision; bootstrap remains limited to an empty dedicated database. G03-C retains rows as replay/conflict floors; TTL and compaction remain disabled because no horizon is approved.

## G03-B local projection write

`GraphProjectionStore.appendVerified` accepts only `GraphEvidenceAdmission.AdmittedRelation`; that nested handle has a private constructor owned by `GraphEvidenceAdmission`. The writer rechecks the requesting principal's Graph purpose and the exact owner evidence before starting work and again inside each Mongo transaction immediately before reading or writing. A stale owner grant or Graph policy denial produces a non-success outcome and no row. Mongo transactions use snapshot reads, primary preference, and majority write concern. Retry is bounded to three transaction attempts for duplicate-key races or explicitly transient transaction errors; other write/commit failures propagate.

The unique logical relation index is the transaction fence. An exact active replay returns `EXISTING` with the exact persisted representation and does not update its timestamps or evidence. A changed proof under the same logical key updates only `lifecycle.state` to terminal `CONFLICT`; the old evidence and canonical `_id` remain untouched, and no competing row is inserted. `markRevokedOrFence` also accepts only an admission-owned typed handle and changes only lifecycle after the owner confirms revocation or conflicting current evidence. No path can reactivate `CONFLICT` or `REVOKED` rows.

Commit acknowledgements are retried at most three times for the same transaction. If the acknowledgement remains unknown, the writer reads the exact scoped logical row with majority read concern. It reports `ACK_RECOVERED` only when the attempted row/lifecycle change is present; otherwise it propagates the unknown commit result. It never maps an unresolved commit or ordinary Mongo failure to `EXISTING`. This is local Mongo atomicity only; source/Device/Graph policy revocation is not one cross-database transaction, so private readers still need current owner checks.

The focused G02/G03 run passed 40 tests with 0 failures/errors: eight admission tests, eleven graph-model tests, eleven schema/RBAC tests, and ten Mongo writer integration tests. The writer tests cover duplicate/restart replay, concurrent identical and conflicting admissions, two occurrences sharing one scoped Device node, tenant/epoch isolation, policy/owner revocation before and at the final write fence, terminal lifecycle revocation, transaction abort, post-commit lost acknowledgement (`ACK_RECOVERED`), and tampered immutable evidence. Mongo `7.0` ran as an authenticated single-node replica set on Windows 11, Java 21, Docker Desktop 29.5.2. Command:

```text
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=GraphEvidenceAdmissionTest,GraphEvidenceModelTest,GraphProjectionSchemaTest,GraphProjectionSchemaIntegrationTest,MongoGraphProjectionStoreIntegrationTest" test
```

These Mongo writer tests use synthetic test-only owner evidence behind `GraphEvidenceAdmission`; they do not claim a persisted production Device relation, runtime wiring, or cross-store atomicity. The real signed Device owner-to-admission vertical remains test-only and is documented under G02-C; production use remains NO-GO.

## G03-C recovery and replay fence

Graph does not persist waiting or authority state. The conceptual state machine is:

| State | Durable in Graph? | Rule |
|---|---|---|
| `AWAITING_SOURCE_PROOF` | No | Owner absence or delayed publication returns `PENDING`/`UNAVAILABLE`; no Graph document is created. |
| `AWAITING_INITIAL_SCORED` | No | Retry admission with the retained opaque owner reference; only the exact current `INITIAL_RUN` score can produce an admitted handle. Rescore or guessed IDs remain denied/conflicting. |
| `ADMITTED` | No | A private handle from `GraphEvidenceAdmission`, tied to principal, owner evidence, and a short deadline; persistence re-resolves owner and purpose before and inside the transaction. |
| `COMMITTED` | `graph_edges` with lifecycle `ACTIVE` | One majority-committed immutable edge row. No separate committed marker or outbox is needed for this single local write. |
| `REVOKED_OR_UNAVAILABLE` | No pending row; existing row may be `REVOKED` | Missing/currently unavailable owner proof prevents append/read confirmation. Explicit owner revocation can fence an existing row; an unavailable owner never becomes an empty result. |
| `CONFLICT` | `graph_edges` lifecycle `CONFLICT` | A changed proof under one logical relation key leaves one row and fences it. Terminal rows cannot be reactivated. |

Recovery is request-driven and bounded; no production worker or replay queue is installed. After source Stage A but before exact initial scoring, admission remains pending without a Graph row. After score publication but before Graph append, resolve the original owner reference again and append only if the same source/domain evidence is still current. After commit before ACK, retry the same transaction's commit acknowledgement three times, then reconcile the exact scoped row; success is surfaced as `ACK_RECOVERED`, while an unresolved/absent result propagates the unknown commit error. Exact duplicate delivery returns the existing immutable row. Arbitrary input order is safe because each admitted relation is independently owner-resolved and the unique index fences local writes; equal occurrence times use `occurrence.token` as a stable tie-break.

There is no atomic transaction spanning Device/source revocation and Graph commit. If revocation lands just after the final owner check, the local write may commit as `ACTIVE`; a subsequent owner-driven `markRevokedOrFence` changes only lifecycle to `REVOKED`. G04-B adds current owner checks and repeated Graph snapshots to private reads, but it cannot close the write-side interval between owner validation and a later revocation. After restoring an older Graph backup, schema verification alone is not proof: reads stay unavailable until current owner evidence and Graph state are checked. Missing Graph rows plus missing owner proof stay unavailable; Graph rows are never used to reconstruct source or Device evidence.

`validUntil` is checked with the injected clock and current owner at admission/write. On replay, an expired stored row returns `UNAVAILABLE/RETENTION_EXPIRED`, does not extend its horizon, and remains in the unique logical index as a replay fence. G03-C deliberately adds no TTL and performs no compaction: no legal retention/replay horizon has been approved, and early deletion could allow old evidence to be reintroduced. A future compacted schema needs an explicit, owner-reviewed replay-floor design; schema v1 has no automatic migration or destructive restore/reset.

The G03-C focused run passed 45 tests with 0 failures/errors: nine Graph admission tests, eleven model tests, eleven schema/RBAC tests, and fourteen Mongo writer/recovery tests. New cases cover delayed score publication, equal-time out-of-order occurrences, commit/revoke race with lifecycle recovery, expiration without TTL/replay extension, and partial projection loss with unavailable owner proof. Mongo `7.0` ran as an authenticated single-node replica set on Windows 11, Java 21, Docker Desktop 29.5.2. Command:

```text
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=GraphEvidenceAdmissionTest,GraphEvidenceModelTest,GraphProjectionSchemaTest,GraphProjectionSchemaIntegrationTest,MongoGraphProjectionStoreIntegrationTest" test
```

The G02-C positive test resolves one relation after adapter reconstruction. It does not test production grants or final cross-database revocation atomicity. G04-B now adds a private bounded read fence and deterministic owner/Graph races; source/Device/Graph still lack a shared atomic snapshot. Merchant remains unsupported until its owner defines a separate Graph purpose and narrow per-occurrence capability.

## G04-A private bounded read

`GraphProjectionReader` is package-private, read-only, and has no Spring, controller, REST, GraphQL, frontend, Kafka, or production bean registration. Its only operation reads neighbors for a typed `GraphOccurrenceRef`; generic traversal and two-hop reads are intentionally absent. Each request binds one exact opaque scope, a distinct `GraphPrivateReadGrant` (`GRAPH_PRIVATE_DIAGNOSTIC_READ`, audience `graph-diagnostics-private`), a `[fromInclusive, untilExclusive)` window, and a server-configured immutable `GraphQueryLimits`. No production Graph grant issuer or verifier is installed; test grants cannot be treated as production authority.

Server ceilings are depth 2, 128 nodes, 256 edges, a 30-day window, 1,000 history rows, page size 100, and a five-second query budget. This implementation uses only one-hop occurrence-to-Device reads. The Mongo adapter requires the `graph_private_reader` principal, applies exact scope/type/occurrence/time predicates, lifecycle validity, `maxTimeMS`, a hard `maxEdges + 1` sentinel, and `_id` keyset order. The reviewed `graph_occurrence_neighbors` index has equality fields through occurrence event time followed by `_id`, so the page order uses the same index. No `skip`, in-memory sort, or disk-use override is used. More than the edge/node ceiling returns `UNAVAILABLE/TRUNCATED_UNVERIFIED` with no edges. An empty Graph result returns `UNAVAILABLE/COVERAGE_UNAVAILABLE`, never verified empty.

The opaque HMAC cursor binds scope hash, edge type, typed occurrence root, range, fixed `asOf`, policy version, last edge ID, and a fingerprint of the bounded candidate set. It expires after the short server query horizon; a changed candidate set invalidates continuation. Before returning each nonempty page, the reader re-resolves every row through Device authority and rechecks the Graph grant. Strict BSON decoding rejects unexpected fields even if an out-of-band writer bypasses collection validation. `GraphReadOutcome` exposes verified edge facts and an optional opaque cursor only; its contract does not assert full graph coverage or verified absence.

The G04-A focused command passed 33 tests with 0 failures/errors: seven `GraphProjectionReaderTest` cases, three schema/codec unit tests, nine authenticated Mongo `7.0` schema/RBAC/query-plan tests, and fourteen Mongo writer/recovery regressions. The Mongo tests prove use of `graph_occurrence_neighbors`, exercise the bounded adapter query, and retain the read-only-role write denial. Environment: Windows 11, Java 21, Docker Desktop 29.5.2. Command:

```text
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=GraphProjectionReaderTest,GraphProjectionSchemaTest,GraphProjectionSchemaIntegrationTest,MongoGraphProjectionStoreIntegrationTest" test
```

G04-A does not yet close TOCTOU after owner revalidation: it does not compare a post-read Graph revision/fence or re-read the candidate set after owner checks, and source/Device/Graph stores have no shared atomic snapshot. A verified page means each included edge passed current owner checks at its check time; it does not prove query completeness or eliminate a revocation racing after the final check. G04-B adds bounded post-owner Graph snapshots and a second full owner pass; the remaining cross-store race after the final checks is not eliminated.

## G04-B final read-time authority fence

`GraphProjectionReader` now performs a bounded Graph read, validates each row and re-resolves it through the Device owner, fetches the exact bounded Graph candidate set again, repeats owner revalidation for every candidate, and fetches the Graph set once more before returning. The exact ordered `GraphStoredEdge` values must match across reads; lifecycle, evidence, admission time, validity, additions, removals, or reorder differences produce `UNAVAILABLE/TRUNCATED_UNVERIFIED` with no edges. The Graph grant is checked before owner I/O and after the final Graph snapshot. The Device bridge itself re-resolves the source receipt and RAW/ENRICHED lineage, current Device assertion and coverage policy, exact current coverage/member anchor, pseudonym/key identity, and exact published `INITIAL_RUN` score against fresh owner snapshots. Changed evidence is `CONFLICT`; owner loss/revocation, policy change, or expiry never returns a stale verified edge.

This is a bounded content fence, not a shared transaction or a monotonic Graph revision counter. Source, Device, Graph, and Graph policy are separate stores, so the protocol cannot form one atomic cross-store snapshot. For a two-hop read, source/Device state is last revalidated during the second owner pass; the final root and Device candidate queries and final Graph-grant check happen afterward. A source/Device revocation after its last owner check, a Graph mutation after the last query covering that row, or a Graph-policy change after the final grant decision can therefore race with return and is not guaranteed to be observed. Changes detected before return, exhausted deadlines, and failed final checks return no verified witness. A successful result describes only the bounded evidence and authority observed at their respective check times; it is retrospective and must not be cached or promoted into a scoring-time fact. Eliminating the residual window requires an owner-specific serializable protocol or a separate cross-store fencing design. No production read grant issuer, runtime reader, or complete-coverage owner capability exists.

An empty Graph query therefore remains `UNAVAILABLE/COVERAGE_UNAVAILABLE`; it is not `VERIFIED_EMPTY` and makes no fraud-free or complete-absence claim. The result DTO returns only nonempty `VERIFIED_BOUNDED` pages or a typed unavailable/denied result. `NOT_PRESENT_IN_BOUNDED_VIEW` cannot be asserted until an owner can authenticate sealed completeness for the exact scope and query window. No generic re-read with a refreshed grant is used to replace missing evidence.

| Race / fault | Expected result | Exact test | Result |
|---|---|---|---|
| Coverage signer/policy revoked after the first owner check | `UNAVAILABLE/AUTHORITY_REVOKED`, no edges | `PrivateDeviceDiagnosticReaderIntegrationTest.deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart` (Testcontainers; actual Device/source owner bridge) | PASS |
| Source receipt deleted after the first owner check | `UNAVAILABLE/AUTHORITY_REVOKED`, no edges | `PrivateDeviceDiagnosticReaderIntegrationTest.graphReadRejectsDeletedSourceReceiptAfterFirstOwnerValidation` (Testcontainers; actual source/Device owner bridge) | PASS |
| History member anchor deleted after the first owner check | `UNAVAILABLE/AUTHORITY_REVOKED`, no edges | `PrivateDeviceDiagnosticReaderIntegrationTest.graphReadRejectsDeletedHistoryMemberAfterFirstOwnerValidation` (Testcontainers; actual Device owner bridge) | PASS |
| Coverage watermark lowered after the first owner check | `UNAVAILABLE/AUTHORITY_REVOKED`, no edges | `PrivateDeviceDiagnosticReaderIntegrationTest.graphReadRejectsLoweredCoverageWatermarkAfterFirstOwnerValidation` (Testcontainers; integrity/policy recheck) | PASS |
| Pseudonym key rotated after the first owner check | `UNAVAILABLE/AUTHORITY_REVOKED`, no edges | `PrivateDeviceDiagnosticReaderIntegrationTest.graphReadRejectsPseudonymKeyRotationAfterFirstOwnerValidation` (Testcontainers; rebuilt owner adapter) | PASS |
| Graph lifecycle BSON changed during owner recheck | `UNAVAILABLE/TRUNCATED_UNVERIFIED`, no edges | `GraphProjectionSchemaIntegrationTest.graphLifecycleFenceLandingDuringOwnerRecheckInvalidatesTheMongoRead` (Testcontainers; real Graph Mongo row) | PASS |
| Candidate set changes between pages | `UNAVAILABLE/TRUNCATED_UNVERIFIED`, no stale continuation | `GraphProjectionReaderTest.pagesAreDeterministicKeysetPagesAndCursorBindsTheExactBoundedDataset` | PASS |
| Graph read grant expires during owner scan | `DENIED/GRANT_DENIED`, no edges | `GraphProjectionReaderTest.grantExpiryDuringOwnerScanReturnsDeniedAndDoesNotReturnTheVerifiedPage` | PASS |
| Owner connection is unavailable | `UNAVAILABLE/OWNER_UNAVAILABLE`, no edges | `GraphProjectionReaderTest.ownerConnectionFailureAndSlowReadReturnUnavailable` (deterministic authority fault) | PASS |
| Query exceeds the bounded time budget, including while preparing the post-owner snapshot | `UNAVAILABLE/DEADLINE_EXCEEDED`, no edges | `GraphProjectionReaderTest.ownerConnectionFailureAndSlowReadReturnUnavailable`; `GraphProjectionReaderTest.deadlineWhilePreparingPostOwnerSnapshotIsNotReportedAsSourceFailure` | PASS |
| Two separately authorized occurrences share one scoped Device pseudonym | Each root returns only its own verified edge | `PrivateDeviceDiagnosticReaderIntegrationTest.deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart` | PASS |
| Concurrent compaction | Not applicable: there is no compactor, TTL, or cleanup worker | No compaction implementation exists; operational retention remains unapproved | N/A |

The G04-B focused Maven batch passed 55 tests with 0 failures/errors and 1 conditionally skipped benchmark. It covered 12 reader unit cases, three schema/codec tests, ten authenticated Graph Mongo schema/RBAC/query-plan tests, fourteen Graph writer/recovery tests, and sixteen private Device/source integration tests (one skipped). The integration tests used Windows 11, Java 21, Docker Desktop 29.5.2, MongoDB 7.0, and Kafka Testcontainers. Command:

```text
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=GraphProjectionReaderTest,GraphProjectionSchemaTest,GraphProjectionSchemaIntegrationTest,MongoGraphProjectionStoreIntegrationTest,PrivateDeviceDiagnosticReaderIntegrationTest" test
```

## G05-A signed Device/source-to-Graph vertical

The positive vertical now exercises two separately signed source events, two independently signed Device associations and coverage evidence, Stage A commit, actual `INITIAL_RUN` score publication to Kafka Testcontainers, and exact `SourceOccurrenceAccess` lookup. The Device owner bridge issues each relation from its current source receipt, RAW/ENRICHED commitments, history membership, pseudonym scope, and exact initial score; Graph admission accepts only each owner reference. Both relations are appended to authenticated Mongo Graph as distinct immutable edges sharing the authorized scoped Device pseudonym. Replaying each admitted relation returns `EXISTING`; after closing the Graph writer and constructing a new Graph reader store plus fresh source and Device read adapters, the private reader returns one independently revalidated edge for each occurrence. Together those two bounded root reads contain exactly the two persisted occurrence-to-Device edges. No fraud score is projected or returned.

This is adapter reconstruction against live Testcontainers stores, not a process-level service restart. Mongo source/Device/Graph and Kafka are ephemeral test infrastructure, and all signing keys/grants are test-only. There is no production signer, Graph policy issuer, runtime wiring, API, scoring behavior, or claim of cross-store atomicity. G05-A acceptance is therefore limited to this positive test vertical. The read is performed at `NOW + 6s` while each admission request has a five-second operation deadline; row `validUntil` follows the current owner evidence/grant horizon instead of the transient request deadline. The operation deadline still fences admission and write work. Replay cannot extend the first persisted horizon.

The focused outcomes are:

| Suite | Tests | Result | Environment |
|---|---:|---|---|
| `GraphEvidenceAdmissionTest` | 10 | PASS, 0 failures/errors | Java 21 |
| `MongoGraphProjectionStoreIntegrationTest` | 14 | PASS, 0 failures/errors | Authenticated MongoDB 7.0 replica set, Docker Desktop 29.5.2 |
| `PrivateDeviceDiagnosticReaderIntegrationTest.deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart` | 1 | PASS, 0 failures/errors | Windows 11, Java 21, MongoDB 7.0, Kafka 3.8.0, Docker Desktop 29.5.2 |

Commands used:

```text
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=GraphEvidenceAdmissionTest" test
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=MongoGraphProjectionStoreIntegrationTest" test
mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PrivateDeviceDiagnosticReaderIntegrationTest#deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart" test
```

The first combined run exposed a test-fixture queue error in the new admission regression; the corrected focused unit class passed 10/10. The expanded two-edge E2E initially exposed an invalid `maxEdges + 1` adapter call to a method capped at the hard maximum; the bounded read now uses the configured maximum and the final E2E passed. No full Maven `verify` was run.

## G06 two-hop owner and scorer evidence

The earlier `GraphSignedABScorerIntegrationTest` crossed a Maven test-classifier boundary and converted a previously positive DTO after the owner had changed. FDP-156 removes that cross-module test JAR and separates the proofs. `SignedGraphTwoHopAuthorityIntegrationTest` is the authenticated signed Source/Device/Kafka/Mongo/Graph owner-side test: it observes a fresh bounded two-hop result, admits a separately signed conflicting Source proof, then observes `CONFLICT` with no witness. `GraphRiskSignalEngineTest.freshConflictBetweenInitialReadAndOccurrenceBindingInvalidatesThePositive` independently proves that a scorer result must use the fresh typed owner outcome returned by `requireOccurrence`; a conflict between the first read and that fence maps to no positive diagnostic. The scorer adapter tests also bind the exact `ScoringContext` and keep the DTO redacted.

These are separate proofs, not a live Graph-to-scorer integration test. There is no production Graph provider, grant issuer, Spring bean, route, or scorer registry entry. The scorer's default adapter denies positive evidence. No score, risk contribution, alert, recommendation, or public Engine Intelligence v2 field is added. The Graph observation remains retrospective and does not establish what was known at scoring time. This limitation must remain explicit until a real, authorized adapter exists.

## G07 two-hop query plan and workload

The opt-in `MongoGraphProjectionWorkloadTest` now measures 100 successful, bounded two-hop reads at each 1k/5k/10k Graph scale. It also executes `explain(EXECUTION_STATS)` for the actual full-scope, lifecycle-filtered, time-bounded reverse-neighbor query, with the reader's sort, limit, batch size, and `maxTime`. All three plans selected `graph_device_neighbors`, had no `COLLSCAN` or blocking `SORT`, and examined only the returned candidate documents. The candidate counts were 3, 19, and 39; each two-hop read issued exactly six Mongo `find` commands and returned a verified witness.

| Synthetic rows | Two-hop p50 / p95 / p99 (ms) | Candidates returned / examined | Reads passed |
|---:|---:|---:|---:|
| 1,000 | 17.189 / 22.716 / 23.443 | 3 / 3 | 100 / 100 |
| 5,000 | 18.488 / 29.201 / 30.162 | 19 / 19 | 100 / 100 |
| 10,000 | 22.287 / 32.562 / 36.683 | 39 / 39 | 100 / 100 |

These are local synthetic measurements on Windows 11, Java 21.0.10, Docker Desktop 29.5.2, and MongoDB 7.0.31 in an authenticated, single-member replica set without CPU or memory quotas. The fixture spreads observations over 256 device buckets, so candidate cardinality is 4/20/40 per hub including the root; this is total-store scaling, not a single-hub stress test. The owner authority is an in-memory test adapter, so these figures isolate the bounded Graph/Mongo path and do not predict end-to-end signed-owner latency or production capacity. The focused workload passed at all three scales; no full Maven `verify` or CI run was performed.

### Historical status snapshot (2026-11-20)

The earlier 1,000-row Graph workload used MongoDB 7.0.31 on Windows 11, Java 21.0.10 and Docker Desktop 29.5.2, without container CPU or memory quotas. Across 1,000 appends, p50/p95/p99 were 8.26/10.39/12.02 ms; across 100 bounded reads, they were 7.01/19.33/24.90 ms. Logical-relation and authorized-occurrence plans examined one document each; the device-hub plan examined 252. These local measurements do not establish production capacity or an SLO; 5,000- and 10,000-row scales were not run.

This historical status snapshot is based on previously pushed HEAD `ca69c3413719a6594764f2a79663e0d21804be71`. On 2026-11-20, GitHub Actions reported completed success on that pushed HEAD: [CI run 44608316386](https://github.com/MiloszPodsiadly/fraud-detection-platform/actions/runs/44608316386) (including Backend Maven Build, Analyst Console Product Gate, and downstream jobs), [Docker Compose CI run 44608316390](https://github.com/MiloszPodsiadly/fraud-detection-platform/actions/runs/44608316390), and Automatic Dependency Submission. At snapshot time the local diff had no SHA and these runs did not cover it. No local full Maven `verify` was run.

The focused signed-source/Device/Graph restart test passed 1/1, followed by the four-method restart/mismatch, signed-conflict, owner-outage and late-revocation regression selection passing 4/4. Both used Testcontainers MongoDB 7.0 and Kafka 3.8.0 on Windows 11, Java 21.0.10, Docker Desktop 29.5.2. The repeated conflict test is included in both selections; these are five successful executions of four methods. The exact regression selector is `PrivateDeviceDiagnosticReaderIntegrationTest#deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart+conflictingValidSignedSourceBindingFencesOneGraphRelationAfterOwnerRestart+transientOwnerMongoOutageDoesNotDurablyRevokeAnEdgeAndRecoveryKeepsItActive+explicitLateSourceRevocationFencesAfterOneHourAndOwnerAdapterRestart`. Graph remains disabled and Merchant remains OPTIONAL BLOCKED.

The Source receipt store still allows at most five transient transaction attempts with 20/40/80/160 ms backoffs: a deterministic maximum of 300 ms waiting, excluding database operations, transaction/lock time and network. Mongo client operation timeout is configured to five seconds; it is not an end-to-end request deadline. Inspection found no request deadline, ingress backpressure, or rate limiter at the attested ingest service/HTTP boundary, so none is invented here.

The opt-in `MongoSourceReceiptStoreIntegrationTest.sourceReceiptRetryBenchmarkReportsOneHundredSamplesPerCase` used MongoDB 7.0 on the same Windows/Java/Docker environment and passed 1/1. Each case had 10 warmups; reported elapsed time covers 100 baseline admissions, 200 concurrent admissions (100 two-writer pairs), or 100 admissions with one injected transient commit failure apiece. The benchmark is enabled with `-Dfdp.source.retry.benchmark=true`. This is local diagnostic data, not an SLO.

| Case | Samples | p50 ms | p95 ms | p99 ms | Total ms | Retries | Configured backoff ms | Mongo commands |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Baseline | 100 | 16.725 | 24.942 | 25.792 | 2145.178 | 0 | 0 | 800 |
| Controlled contention | 200 | 17.544 | 46.340 | 56.581 | 4735.710 | 0 | 0 | 1700 |
| One transient failure per admission | 100 | 46.082 | 61.888 | 63.277 | 5255.840 | 100 | 2000 | 1600 |

The benchmark does not represent production capacity, a latency target, or a retry-exhaustion workload. No total ingress budget or backpressure claim is established.
### Operational preparation and fail-safe

- Keep Graph admission, reads, and scoring disabled. The current implementation is package-private and has no production grant issuer, runtime writer/reader registration, route, or consumer.
- Bootstrap only an empty, isolated Graph database with the dedicated schema-owner identity. Runtime writer and reader startup validate the exact collection metadata, validator, indexes, and effective principal privileges; startup does not scan stored rows or create, repair, migrate, or drop schema. Each bounded runtime read strictly decodes rows and revalidates current owner authority; startup metadata checks do not claim to prove every historical row valid.
- Keep schema-owner, writer and reader Mongo identities separate and least-privileged. No production credentials or grants are configured by this change.
- On an ambiguous write result, reconcile only the exact logical relation and expected immutable evidence. If current owner evidence is unavailable or differs without a proven immutable contradiction, leave the result `UNAVAILABLE` and do not change lifecycle; only an owner-validated typed conflict can fence `CONFLICT`. Do not rebuild from stale Graph rows or run a bulk recovery worker.
- There is no TTL index, compactor, or approved retention policy. Do not delete or compact Graph rows to imply completeness or authority.
- Restore any backup into an isolated, quarantined database. Validate the schema and indexes, then re-resolve every row against current source, Device, coverage and Graph grant authority before allowing reads; otherwise keep Graph disabled and unavailable.
- Rollback means keeping or returning the Graph path to disabled state. Do not reset, drop, or rewrite persisted evidence as a rollback shortcut.

G00-B was an architecture-only gate and did not run tests. G02-A focused verification covered domain canonicalization, nanosecond score identity, signed Device evidence, authenticated read-only Mongo access, owner restart resolution, wrong epoch, forged reference, wrong principal, revoked coverage, and source/scope/replay regression (75 tests, 0 failures/errors). The G02-C command was `mvn "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=GraphEvidenceAdmissionTest,GraphEvidenceModelTest,MerchantAssertionVerifierTest,PrivateDeviceDiagnosticReaderIntegrationTest#deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart" test`; 32 tests passed with 0 failures/errors.

G02-B evaluated the Merchant owner path without adding a bridge. Although it retains signed observation and coverage bytes, current source grants do not authorize Graph projection and its private read result is an aggregate diagnostic, not a transferable per-occurrence relation capability. Focused negative tests assert Merchant signer roles remain limited to observation/coverage and that Merchant edge/node kinds cannot be represented. Existing Merchant tests separately cover source scope, mapping, population, exact score, coverage gaps, and revocation. No same-merchant join is admitted, including within one source scope; `MERCHANT_GRAPH_RELATION_NOT_AUTHORIZED` remains the explicit status until the owner grants and exposes that capability. The G02-B focused run passed 29 tests with 0 failures/errors (11 graph-model, 12 Merchant assertion-verifier, and six selected Mongo/Testcontainers Merchant admission regressions).

## Gate result

G02-A/G02-C, G03-A/B/C, G04-A/B, G05-A/B/C, G06, and G07 result: GO for the disabled test-only Device relation, strict Mongo schema/RBAC, local transactional writes, bounded recovery and reader, the signed A/B two-hop scorer-boundary proof, layered adversarial regressions, and the measured 1,000/5,000/10,000-row workloads. The reader's repeated owner checks and Graph candidate-set fences are bounded freshness checks, not a shared atomic snapshot. There is still no production Graph service or runtime writer/reader registration. G02-B remains OPTIONAL BLOCKED for Merchant (`MERCHANT_GRAPH_RELATION_NOT_AUTHORIZED`). Merchant joins remain denied, and production use, public API, scoring effects, and all cross-source joins remain NO-GO.
