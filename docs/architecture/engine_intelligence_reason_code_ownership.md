# Engine Intelligence reason-code ownership

This matrix records which scoring engine can emit each bounded public reason code and where shared codes have a real
producer. The Java policy and cross-language fixture are the enforcement sources; the Analyst Console validates the
same per-engine matrix.

## Ownership matrix

| Owner or sharing set | Reason codes | Producer evidence and sharing rationale |
| --- | --- | --- |
| Rules and Python ML | `DEVICE_NOVELTY`, `COUNTRY_MISMATCH`, `PROXY_OR_VPN`, `MERCHANT_CONCENTRATION` | Rules V2 emits these from canonical facts in `RulesScoringPolicyV2`; the Python feature contribution map emits them from corresponding model features. Velocity has no producer for these codes. |
| Rules, Python ML, and Velocity | `RAPID_PLN_20K_BURST` | All three derive the same rapid-transfer burst signal. Rules V2 and Velocity use the shared transaction-window condition; Python ML maps `rapidTransferBurst` to this code. |
| Python ML and Velocity | `TRANSACTION_VELOCITY`, `RECENT_AMOUNT_ACCUMULATION` | The Python feature map and `VelocitySignalPolicy` both interpret the same recent-window facts. Rules has no direct producer for these two codes. |
| Rules only | `HIGH_VELOCITY`, `HIGH_AMOUNT_ACTIVITY`, `HIGH_TRANSACTION_AMOUNT`; `DEVICE_NOVELTY_SIGNAL`, `COUNTRY_MISMATCH_SIGNAL`, `PROXY_OR_VPN_SIGNAL`, `RAPID_TRANSFER_BURST_SIGNAL`, `HIGH_RISK_FLAGS_PRESENT`, `VELOCITY_THRESHOLD_EXCEEDED`, `AMOUNT_ACTIVITY_THRESHOLD_EXCEEDED`, `RAPID_TRANSFER_PATTERN_MATCHED`, `FEATURE_STATUS_INVALID`, `FEATURE_STATUS_WRONG_ACCESSOR`, `FEATURE_STATUS_NOT_ALLOWED` | The first three are emitted by Rules V2. The remaining codes are emitted by the Rules signal adapter and its feature-status validation. |
| Python ML only | `RECENT_TRANSACTION_SPIKE`, `MODEL_HIGH_RISK`, `LOW_MODEL_RISK`, `ML_MODEL_SIGNAL`, `ML_MODEL_UNAVAILABLE`, `ML_MODEL_TIMEOUT`, `ML_MODEL_INVALID_RESPONSE`, `ML_SCORE_MISSING`, `ML_SCORE_OUT_OF_RANGE`, `ML_INFERENCE_TIMESTAMP_MISSING`, `ML_MODEL_METADATA_MISSING`, `ML_AVAILABILITY_METADATA_MISSING`, `ML_AVAILABILITY_METADATA_INVALID`, `ML_CLIENT_ERROR` | The Python model maps `recentTransactionCount` to `RECENT_TRANSACTION_SPIKE` and `suspiciousFactRatio` to `MODEL_HIGH_RISK`; the Java ML adapter owns inference and response failure codes. `LOW_MODEL_RISK` has no active producer in the current Python inference source. It remains ML-only for existing public enum and golden-fixture compatibility. |
| Velocity only | `VELOCITY_FEATURES_UNAVAILABLE`, `VELOCITY_FEATURE_TYPE_INVALID`, `VELOCITY_FEATURE_VALUE_INVALID`, `VELOCITY_FEATURES_INCONSISTENT` | Emitted by Velocity input validation and policy evaluation. `RECENT_TRANSACTION_SPIKE` is not a Velocity code: its active producer is the Python feature map. |
| Device Risk only | The `DeviceRiskSignalReasonCode` enum values in `deviceOwnedReasonCodes` in the shared fixture | Emitted by `DeviceRiskSignalEngine` and `DeviceRiskSignalEvaluationMapper`. `DEVICE_SIGNALS_UNSUPPORTED` is declared by the Device enum and retained as Device-owned, although no current call site was found. |
| Orchestrator, any engine result | `ORCHESTRATOR_ENGINE_EXCEPTION`, `ORCHESTRATOR_ENGINE_NULL_RESULT`, `ORCHESTRATOR_ENGINE_PUBLICATION_FAILURE`, `ORCHESTRATOR_ENGINE_REJECTED`, `ORCHESTRATOR_ENGINE_TIMEOUT` | `FraudEnginePublicationFactory` can attach these execution outcomes to the affected engine result, so they are accepted for each canonical engine identity. |

The sharing sets are intentionally narrow. In particular, `MODEL_HIGH_RISK` is rejected on Rules, `DEVICE_NOVELTY` is
rejected on Velocity, and Device-owned codes are rejected on all non-Device engines. No public wire schema changed.

## Producer and consumer path

The canonical replay generator (`scripts/generate-canonical-replay.sh`) creates enriched-transaction JSONL inputs; it
does not create scored events or `engineIntelligence` reason codes. Its deterministic identity/output behavior is
covered by `scripts/generate-canonical-replay.test.mjs`. Reason-code ownership therefore starts at scoring, with the
producer and mapper tests below, and is checked against the full-path public response fixture.

1. Rules, Python ML, Velocity, and Device Risk produce bounded reason codes through their scoring policies/adapters.
2. `FraudEngineReasonCodeNormalizer` checks per-engine ownership before aggregation; `EngineIntelligenceReasonCodePolicy`
   is the canonical Java allowlist.
3. `TransactionScoredEventMapper` publishes the normalized engine results. Alert-service projection and feedback-dataset
   mapping preserve only contract-valid bounded reason codes.
4. OpenAPI contracts describe the public result shape; Analyst Console `engineIntelligenceContractValidation.js`
   validates the same ownership matrix before rendering.

The parity fixture is `common-events/src/test/resources/fixtures/engine-intelligence/engine_reason_code_ownership_contract.json`.
Its exact set is compared with the Java policy and the UI allowlists. The negative semantic fixture covers an ML code on
Rules and `DEVICE_NOVELTY` on Velocity.

## Source and test evidence

- Rules producers: `fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/RulesScoringPolicyV2.java`,
  `fraud-scoring-service/src/main/java/com/frauddetection/scoring/engine/rules/RuleBasedSignalEngine.java`, and
  `fraud-scoring-service/src/main/java/com/frauddetection/scoring/engine/rules/RuleBasedSignalReasonCode.java`.
- Python ML producers: `ml-inference-service/app/inference/model_runtime.py` and
  `fraud-scoring-service/src/main/java/com/frauddetection/scoring/engine/ml/PythonMlSignalEngine.java`.
- Velocity producers: `fraud-scoring-service/src/main/java/com/frauddetection/scoring/engine/velocity/VelocitySignalPolicy.java`
  and `VelocityInputValidator.java`.
- Device and orchestrator producers: `fraud-scoring-service/src/main/java/com/frauddetection/scoring/device/DeviceRiskSignalEngine.java`,
  `DeviceRiskSignalEvaluationMapper.java`, and `fraud-scoring-service/src/main/java/com/frauddetection/scoring/orchestration/FraudEnginePublicationFactory.java`.
- Enforcement and transport: `common-events/src/main/java/com/frauddetection/common/events/intelligence/EngineIntelligenceReasonCodePolicy.java`,
  `fraud-scoring-service/src/main/java/com/frauddetection/scoring/orchestration/aggregation/FraudEngineReasonCodeNormalizer.java`,
  and `fraud-scoring-service/src/main/java/com/frauddetection/scoring/mapper/TransactionScoredEventMapper.java`.
- Java parity and producer guards: `EngineIntelligenceReasonCodeOwnershipFixtureTest`,
  `FraudEngineReasonCodeOwnershipProducerTest`, and `FraudEngineReasonCodeNormalizerTest`.
- End-to-end contract consumers: `EngineIntelligenceProjectionPolicyTest`,
  `EngineIntelligenceFeedbackDatasetRecordSchemaParityTest`, and
  `alert-service/src/test/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceOpenApiContractTest.java`,
  `analyst-console-ui/src/engineIntelligence/engineIntelligenceContractValidation.test.js`, and
  `contract-fixtures/public-api/engine-intelligence-full-path-composition-response.json`.
- Active model feature-code evidence: `ml-inference-service/tests/test_model.py`.
