# FDP-82 Engine Contract Foundation

Status: branch scope summary.

## Summary

This branch adds a multi-engine scoring contract foundation to the existing fraud intelligence platform.

## Introduced

- Product scope and non-goals documentation.
- Fraud intelligence glossary.
- Multi-engine scoring architecture foundation.
- `FraudEngineResult` contract.
- `FraudEngineType`, `FraudEngineStatus`, and `FraudEngineConfidence`.
- `FraudEngineContribution` and `FraudEngineEvidence`.
- Bounded collection counts, controlled explanation vocabularies, and status consistency validation.
- Tolerant additive JSON consumption with strict validation of documented fields.
- JSON contract examples.
- Serialization, compatibility, validation, documentation, and isolation tests.

## Not Introduced

- No runtime scoring change.
- No orchestrator.
- No `ScoringContext`.
- No `engineResults` field in events.
- No API or UI.
- No feedback loop.
- No automatic approve or decline.
- No ML final decision source.

An engine result is not a final banking decision and does not perform core banking authorization.
