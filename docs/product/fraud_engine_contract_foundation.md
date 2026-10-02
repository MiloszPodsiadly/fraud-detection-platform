# Fraud Engine Contract Foundation

Status: current contract scope.

## Summary

The multi-engine scoring contract provides a foundation for the fraud intelligence platform.

## Introduced

- Product scope and non-goals documentation.
- Fraud intelligence glossary.
- Multi-engine scoring architecture foundation.
- `FraudEngineResult` contract.
- `FraudEngineType`, `FraudEngineStatus`, and `FraudEngineConfidence`.
- `FraudEngineContribution` and `FraudEngineEvidence`.
- Bounded collection counts, controlled explanation vocabularies, machine-readable origins and status reasons,
  and status consistency validation.
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
