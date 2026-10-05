# Alert Service Source of Truth

The following stores are authoritative. API DTOs and trust-level views are projections; if a projection disagrees with its source of truth, the source of truth wins.

## Regulated Mutation Lifecycle

Authoritative source: `RegulatedMutationCommandDocument` in the `regulated_mutation_commands` collection.

Projection: regulated mutation inspection DTOs.

## Business Alert Decision State

Authoritative source: business fields on `AlertDocument`.

Projection: response snapshots and cached decision response views.

Before an analyst decision, an alert may reconcile to a newer alert-worthy scoring occurrence. Once `analystDecision`
or `decidedAt` is present, the alert owns historical decision evidence: its source occurrence, score, risk, reason codes,
model metadata where captured, feature snapshot, and evidence snapshot are not rewritten by rescoring. Current transaction
classification continues in `ScoredTransactionDocument` and current suspicious-transaction projections. Mongo transaction
conflicts roll back a concurrent stale reconciliation; redelivery then observes the decision and preserves its evidence.

## Transactional Outbox

Authoritative source: `TransactionalOutboxRecordDocument` in the `transactional_outbox_records` collection.

Projection: `AlertDocument.decisionOutboxStatus` and related alert-level outbox cache fields.

## Trust Incidents

Authoritative source: `TrustIncidentDocument` in the `trust_incidents` collection.

History source: regulated mutation audit events. A dedicated `trust_incident_events` stream is deferred.

## Audit Evidence

Authoritative sources:

- durable audit event chain
- `AuditEventDocument` in the `audit_events` collection
- `ExternalAuditAnchorPublicationStatusDocument` in the `audit_external_anchor_publication_status` collection
- signature verification status

API DTOs are read models over those sources.

## Trust Level

`/system/trust-level` is a derived view only. It is not a separate source of truth and cannot override audit, outbox, trust incident, or regulated mutation state.

## Explicit Limitations

The platform does not provide distributed ACID, exactly-once Kafka delivery, WORM storage, legal notarization, or a regulator-certified archive.
