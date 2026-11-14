# Private Device signed assertions

Date: 2026-11-12

Status: protocol and test evidence for the private Device trust path. Independent review, institutional approval, and reconciliation with the primary scope/reviewer/masterplan sources remain open. Overall implementation and release gates are recorded in [Device Verified Source Admission](../adr/device_verified_source_admission.md).

## Implementation boundary

`DeviceAssertions` and `DeviceAssertionVerifier` are package-private types in `feature-enricher-service`. They have no Spring bean, configuration activation, persistence dependency, public event, or trusted-history reader. The verifier checks cryptographic authenticity and exact expected bindings; its opaque result is not a durable admission receipt or a statement that history is complete.

The implementation reuses `SourceSignedInput` v1 and `SourceSignatureVerifier` for Ed25519 over the exact original assertion bytes and all parsed envelope claims. Signature input retains the existing `fraud-source-attestation/v1` domain; signed purpose and fixed audience distinguish Device observation and coverage rights. It never reserializes a supplied DTO to reconstruct the signed payload. The purpose must be `DEVICE_OBSERVATION_ASSERTION` or `DEVICE_COVERAGE_ASSERTION`, with audience `feature-enricher-device-private`. A transaction-source grant cannot satisfy either role.

Each Device grant explicitly pins the source-origin issuer, institution, source, source epoch, population definition, pseudonym-key version, binding authority and provider basis. The observation and coverage roles require different key material. Device keys cannot reuse a configured transaction-origin key, and the Device issuer must differ from the source-origin issuer. No trust grant is registered by default or exposed through Spring properties.

## Protocol and limits

The existing signature envelope has exactly 13 fields: `protocolVersion`, `algorithm`, `issuer`, `institution`, `source`, `sourceEpoch`, `signingKeyVersion`, `sourceEventId`, `eventTime`, `purpose`, `audience`, `payloadCommitment`, and `signature`. Its existing bounds remain 4,096 bytes, duplicate detection, strict UTF-8, three levels of JSON nesting, bounded strings/numbers, one document, and canonical unpadded base64url Ed25519 signature encoding. Envelope version is 1 and algorithm is Ed25519.

The assertion payload has its own `assertionVersion=1`, a maximum of 8,192 original bytes, at most two levels of JSON nesting, 128-character strings, 19-character numbers, and one document. Every required field must exist; all other fields, duplicate fields, non-integral numbers, noncanonical timestamps, invalid UTF-8, and unsupported assertion versions are rejected. Time values must be canonical UTC `Instant` strings at millisecond precision. Identifiers use the existing bounded source-reference grammar; commitments and pseudonyms use 64 lowercase hexadecimal characters. Sequence/generation values are positive signed 64-bit integers. Pseudonym key versions are 1–65,535.

The names adapt the prompt's proposed `DeviceObservationAssertion` / `DeviceCoverageAssertion` to private nested immutable `DeviceAssertions.Observation` / `DeviceAssertions.Coverage` records. Only the verifier can construct an opaque `VerifiedObservation` or `VerifiedCoverage`. Claim records remain untrusted when constructed or parsed independently. Private claim, binding, policy and verified types are marked `JsonIgnoreType` and have redacted `toString` output; denials are a bounded enum.

| Observation payload field | Binding or validation |
| --- | --- |
| `assertionVersion` | Exactly 1 |
| `sourceIssuer`, `institution`, `source`, `sourceEpoch` | Exact configured source-origin scope, distinct from the Device issuer in the signature envelope |
| `sourceEventId`, `eventTime` | Exact original source evidence and signature envelope event/time |
| `receiptId` | Exact expected receipt and recomputed `SourceReceiptIdentity.identity` of the verified source scope |
| `rawCommitment`, `enrichedCommitment` | Exact values from trusted expected binding; neither is substituted with the original source payload commitment |
| `transactionKey`, `customerToken`, `deviceToken` | Exact expected private transaction reference and institution-scoped pseudonyms |
| `pseudonymKeyVersion` | Exact grant and expected binding version; never inferred from signing-key version |
| `sourceSequence` | Positive integer at or after the authorized sequence origin; durable monotonicity and uniqueness are enforced by the epoch-scoped ledger |
| `observedAt` | Between event time minus 300 seconds and plus 120 seconds, and no more than 120 seconds in the future |
| `bindingAuthority`, `providerBasis` | Exact explicitly permitted authority/basis IDs |
| `populationId`, `populationDefinitionVersion`, `populationDefinitionCommitment` | Exact server-owned authorized population definition |
| `policyVersion` | Exact Device signing grant policy version |

| Coverage payload field | Binding or validation |
| --- | --- |
| `assertionVersion`, `sourceIssuer`, `institution`, `source`, `sourceEpoch` | Version 1 and exact source-origin scope |
| `coverageId`, `watermark` | Exact signature envelope event/time; watermark at most 120 seconds in the future |
| `populationId`, `populationDefinitionVersion`, `populationDefinitionCommitment` | Exact authorized population; unknown or missing population is rejected |
| `inclusionRule`, `exclusionRule` | Exact versioned rule IDs from the configured population definition |
| `pseudonymKeyVersion` | Exact explicitly authorized pseudonym version |
| `sequenceOrigin`, `fromSequence`, `throughSequence` | Exact authorized origin; origin ≤ from ≤ through |
| `coverageStart` | Before watermark and no earlier than the explicitly authorized historical coverage start |
| `coverageGeneration`, `previousCoverageCommitment` | First generation starts at sequence origin with zero previous commitment; later generations require nonzero previous commitment |
| `coverageCommitment` | Bounded commitment to the attested covered population/range; reconciled against the exact staged batch and prior generation |
| `policyVersion` | Exact coverage signing grant policy version |

## Authorization decision matrix

| Input / condition | Outcome |
| --- | --- |
| Independently signed Device proof; configured role/key/scope/population; exact binding | Opaque verified assertion only |
| Missing, expired, revoked or changed original transaction-source evidence | `SOURCE_NOT_AUTHORIZED` |
| Oversized/absent original assertion payload | `PAYLOAD_REJECTED` |
| Unknown key, invalid signature, wrong version/algorithm/purpose/audience/envelope scope, malformed envelope | `SIGNATURE_OR_POLICY_DENIED` |
| Missing/unknown/duplicate payload fields, invalid encoding/type/range/timestamp/version | `MALFORMED_ASSERTION` |
| Mismatched source/receipt/RAW/ENRICHED/transaction/customer/device binding | `BINDING_MISMATCH` |
| Wrong binding authority, provider basis or observation policy | `AUTHORITY_NOT_AUTHORIZED` |
| Unknown population, changed definition/rules/origin or unapproved historical start | `POPULATION_NOT_AUTHORIZED` |
| Unsupported pseudonym-key version or inferred rotation continuity | `PSEUDONYM_KEY_NOT_AUTHORIZED` |
| Incompatible observation time / watermark | `TIME_NOT_AUTHORIZED` |
| Later use after key, population, pseudonym or other grant changes | `currentDenial` returns `POLICY_CHANGED`, or `SOURCE_NOT_AUTHORIZED` if source authority changed |

`currentDenial` checks the complete immutable grant and both current source-origin and Device signing policies. A change of pseudonym policy, population policy, key material, validity or revocation cannot reuse prior verification evidence. Signing-key rotation may preserve the existing pseudonym version only under a separately configured Device grant. Neither signing-key rotation nor an epoch change grants pseudonym/history continuity.

## Test evidence

`DeviceAssertionVerifierTest` generates disposable Ed25519 keypairs in memory for transaction origin, observation, and coverage. No private signing key or production trust entry is stored in the repository. It tests 57 cases, including real signatures with incorrect authorization/bindings, repurposed source proof, payload bit changes, exact-byte formatting changes, key mismatch/expiry/revocation/rotation, unknown scope and population, customer/device forgery, receipt/RAW mismatch, duplicate/unknown/missing fields, UTF-8 and input limits, canonical types/time, sequence-origin bounds, and pseudonym continuity denial.

The expected RAW/ENRICHED commitments and pseudonyms in these fixtures are synthetic test values. The original source evidence is cryptographically verified, but the fixtures do not establish a durable source receipt or authenticated RAW/ENRICHED lineage. The unchanged `DeviceRiskAdmissionConfigurationTest` contributes four regression cases proving that runtime admission remains disabled and attempted enablement still fails startup.

Executed from the repository root in PowerShell:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" -pl feature-enricher-service -am "-Dtest=DeviceAssertionVerifierTest,DeviceRiskAdmissionConfigurationTest" test
```

Final result on 2026-11-12: **BUILD SUCCESS; 61 tests, 0 failures, 0 errors, 0 skipped** (57 verifier cases and 4 admission-configuration cases). Static review includes the authorized sequence-origin boundary and its regression case. The initial sandbox attempt failed reading a local dependency JAR; the targeted command completed after approved escalation. Full Maven `verify` was not run.

## Changed-file inventory

| Path | Change |
| --- | --- |
| `feature-enricher-service/src/main/java/com/frauddetection/enricher/device/DeviceAssertions.java` | Private immutable claim records and strict bounded parsing |
| `feature-enricher-service/src/main/java/com/frauddetection/enricher/device/DeviceAssertionVerifier.java` | Independent role grants, source and expected-binding checks, population/time/pseudonym policy, opaque verification evidence and current-policy recheck |
| `feature-enricher-service/src/test/java/com/frauddetection/enricher/device/DeviceAssertionVerifierTest.java` | Disposable real Ed25519 fixtures and 57 verification/regression cases |
| `docs/architecture/device_signed_assertions.md` | Protocol, authorization matrix, test command/result and remaining gates |
| `docs/adr/device_verified_source_admission.md` | Records the source/Device authority split, epoch identity, current implementation and release gates |

## Limits and review gates

- The verifier's fixtures use synthetic expected commitments and pseudonyms. They do not establish a production source receipt, production issuer, real source population, key custody, retention approval, or production Mongo role.
- Cryptography authenticates an issuer's contractual coverage statement; it cannot prove that an upstream source omitted no events.
- Epoch-aware persistence, current grant checks, snapshot completeness, replay, and private SCORED binding are implemented and have separate Mongo/Kafka evidence in [verified admission](device_verified_admission.md), [private diagnostics](device_private_diagnostics.md), and [recovery evidence](device_resilience.md).
- Independent review, institutional/privacy approval, production migration and active Device engine use remain blocked. The Device engine is still absent from the active registry and public output.
