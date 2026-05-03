# Alert Service Source of Truth

FDP-27 treats the following stores as authoritative. API DTOs and trust-level views are projections; if a projection disagrees with its source of truth, the source of truth wins.

## Regulated Mutation Lifecycle

Authoritative source: `RegulatedMutationCommand`.

Projection: regulated mutation inspection DTOs.

## Business Alert Decision State

Authoritative source: business fields on `AlertDocument`.

Projection: response snapshots and cached decision response views.

## Transactional Outbox

Authoritative source: `transactional_outbox_records`.

Projection: `AlertDocument.decisionOutboxStatus` and related alert-level outbox cache fields.

## Trust Incidents

Authoritative source: `TrustIncidentDocument`.

History source: regulated mutation audit events. A dedicated `trust_incident_events` stream is deferred.

## Audit Evidence

Authoritative sources:

- durable audit event chain
- external publication status
- signature verification status

API DTOs are read models over those sources.

## Trust Level

`/system/trust-level` is a derived view only. It is not a separate source of truth and cannot override audit, outbox, trust incident, or regulated mutation state.

## Explicit Limitations

FDP-27 does not provide distributed ACID, does not provide exactly-once Kafka delivery, does not provide WORM storage, does not provide legal notarization, and is not a regulator-certified archive.
