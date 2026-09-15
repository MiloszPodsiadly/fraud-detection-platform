# Reason Codes

Status: current product documentation.

## Purpose

`ReasonCode` is a controlled scoring and investigation signal used to explain why a transaction contributed to a risk score.

## Non-claims

A `ReasonCode` is not confirmed fraud.
A `ReasonCode` is not evidence by itself.
A `ReasonCode` is not an analyst decision.
A `ReasonCode` is not final outcome.
A `ReasonCode` is not proof that a fraud case exists.
A `ReasonCode` severity or category is not final risk level.

## UNKNOWN Semantics

`UNKNOWN` is a compatibility and parse diagnostic marker for unsupported, malformed, blank, null, or future reason-code input.
`UNKNOWN` must not be treated as a supported scoring signal.
Unsupported input must remain visible through diagnostics, metadata, and low-cardinality metrics.

## Rapid Transfer Semantics

`RAPID_PLN_20K_BURST` is the current bounded Rules V2 rapid-transfer scoring reason.
It does not mean a fraud case exists, fraud is confirmed, or a case was created.
Fraud-case eligibility is derived from canonical feature facts by the alert workflow, not from a deleted
case-candidate reason code.

## Compatibility

Feature names and retired aliases are not public reason codes. Unsupported input remains diagnostic-only and is not
projected as supported scoring evidence.

## Out Of Scope

- No `EvidenceDocument`.
- No `SuspiciousTransaction`.
- No case lifecycle change.
- No UI change.
- No final outcome semantics.
- No Mongo migration.

