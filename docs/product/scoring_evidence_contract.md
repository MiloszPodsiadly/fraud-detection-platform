# Scoring Evidence Contract

Status: current product documentation.

## Purpose

ScoringEvidence is a typed scoring explanation contract emitted by scoring services.
It explains scoring context and supports downstream investigation context.

It travels alongside existing scoring outputs so consumers can inspect structured scoring context without
reinterpreting unbounded score details.

## Non-claims

ScoringEvidence is internal scoring explanation context only.
ScoringEvidence is not a fraud decision.
ScoringEvidence is not an analyst disposition.
ScoringEvidence is not a final outcome.
ScoringEvidence is not a legally binding evidence record.
ScoringEvidence does not provide write-once immutable storage guarantees.
ScoringEvidence is not a third-party attested record.
ScoringEvidence is not independently verified by an external authority.
ScoringEvidence does not prove that a fraud case exists.
ScoringEvidence severity is not final risk level.

## Status Semantics

| Status | Meaning |
| --- | --- |
| `AVAILABLE` | A supported reason code produced a typed scoring evidence item. |
| `PARTIAL` | Diagnostic context exists, but supported scoring evidence was incomplete. |
| `UNAVAILABLE` | The scoring source or runtime could not provide supported evidence. |
| `ERROR` | Diagnostic evidence represents an explicit scoring-evidence creation error. |
| `NOT_APPLICABLE` | The evidence category was not applicable to the scoring path. |
| `LEGACY` | Evidence describes a legacy or fallback scoring path. |

`PARTIAL`, `UNAVAILABLE`, and `ERROR` are intentionally distinct. `STALE` is not part of this contract.

## Source Semantics

| Source | Meaning |
| --- | --- |
| `RULE_BASED_SCORING` | Evidence created by rule-based scoring. |
| `ML_MODEL` | Evidence created from supported model scoring output while the model was available. |
| `ML_RUNTIME` | Evidence describing model runtime unavailability or runtime diagnostics. |
| `FEATURE_SNAPSHOT` | Evidence derived from bounded feature-snapshot context. |
| `SCORING_FALLBACK` | Evidence describing fallback scoring behavior. |
| `LEGACY_SCORING` | Evidence describing legacy scoring compatibility behavior. |

## Relationship To ReasonCode

ReasonCode remains the source of truth for scoring reason taxonomy.
ScoringEvidence references reason codes by canonical wire value and adds typed explanation context.

`UNKNOWN` is diagnostic only. It must not become supported scoring evidence.
Unsupported, future, blank, or malformed reason-code input creates diagnostic evidence or diagnostic metadata
without storing the raw unsupported value.

ScoringEvidenceItem `evidenceId` is stable within a single scored event. It is not a globally unique persistence
identifier. Downstream persistence layers must combine it with event identity if they need global uniqueness.

## Relationship To FDP-57 Alert-Service EvidenceDocument

FDP-57 EvidenceDocument describes alert-service evidence projection semantics.
ScoringEvidence is earlier scoring explanation context and does not mutate alert-service persistence in this branch.

Downstream services may later project scoring evidence into their own evidence models, but this branch does not add:

- `AlertDocument.evidenceSnapshot`
- `SuspiciousTransaction`
- `FraudCaseEvidenceSummary`
- evidence timeline behavior
- case lifecycle mutation

## Compatibility

FDP-58 is additive.

The following fields remain:

- `reasonCodes`
- `scoreDetails`
- `featureSnapshot`
- `explanationMetadata`

Existing downstream consumers are not forced to migrate in this branch.
Consumers that ignore `scoringEvidence` continue reading the existing scoring outputs.
Consumers that read `scoringEvidence` must not use it as a fraud decision or final outcome.

## Attributes Safety Policy

Attributes must be safe, bounded metadata only.

Allowed values are bounded primitives and lists of bounded primitive values:

- `String`
- `Number`
- `Boolean`
- enum names represented as strings

Attributes must not contain:

- PII
- raw model payload
- raw unsupported reason-code value
- full `featureSnapshot` dumps
- nested unbounded JSON
- high-cardinality sensitive identifiers

Sensitive key patterns such as customer, account, card, IBAN, PESEL, SSN, email, phone, name, address, raw,
payload, featureSnapshot, and modelPayload are rejected.

## Supported Evidence Vs Diagnostic Evidence

Supported evidence has `status = AVAILABLE`, a canonical supported `reasonCode`, a typed `evidenceType`, and a
source matching the scoring path.

Diagnostic evidence has `evidenceType = DIAGNOSTIC` or a non-available status. It explains missing, unsupported,
fallback, or unavailable scoring context. Diagnostic evidence does not create a supported scoring signal.

High or critical scoring output without supported reason codes must create explicit partial or unavailable
diagnostic evidence instead of silently succeeding with an empty supported-evidence set.

ML fallback is represented through runtime or fallback evidence. It must not appear as `ML_MODEL` `AVAILABLE`.

## Out Of Scope

FDP-58 does not add:

- legally immutable archival storage
- third-party attestation
- legally binding evidence-record semantics
- independent external-authority verification
- alert evidence snapshot persistence
- UI
- public evidence API
- public scoring evidence API
- `AlertDocument.evidenceSnapshot`
- `SuspiciousTransaction`
- `FraudCaseEvidenceSummary`
- evidence timeline
- final outcome
- analyst mutation
- manual evidence editing
- case lifecycle mutation
- Mongo backfill
