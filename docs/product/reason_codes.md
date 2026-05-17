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

## RAPID_TRANSFER_FRAUD_CASE Semantics

`RAPID_TRANSFER_FRAUD_CASE` is a legacy-compatible wire value for a rapid-transfer case-candidate scoring signal.
It does not mean a fraud case exists.
It does not mean fraud is confirmed.
It must not be displayed as a verdict.

## Compatibility

Legacy aliases:

- `HIGH_AMOUNT` -> `HIGH_TRANSACTION_AMOUNT`
- `countryMismatch` -> `COUNTRY_MISMATCH`
- `deviceNovelty` -> `DEVICE_NOVELTY`
- `proxyOrVpnDetected` -> `PROXY_OR_VPN`
- `rapidTransferFraudCaseCandidate` -> `RAPID_TRANSFER_FRAUD_CASE`

## Out Of Scope

- No `EvidenceDocument`.
- No `SuspiciousTransaction`.
- No case lifecycle change.
- No UI change.
- No final outcome semantics.
- No Mongo migration.

