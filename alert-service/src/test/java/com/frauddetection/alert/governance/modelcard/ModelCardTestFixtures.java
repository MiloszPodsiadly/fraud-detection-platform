package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

final class ModelCardTestFixtures {

    static final String GENERATED_AT = "2026-10-13T10:15:30Z";
    static final String SUMMARY_GENERATED_AT = "2026-10-13T10:15:29Z";
    static final String ARTIFACT_SHA = "a".repeat(64);
    static final List<String> INTENDED_USE = List.of(
            "EXACT_MODEL_OFFLINE_EVALUATION",
            "RULES_VS_ML_DIAGNOSTIC_COMPARISON",
            "SHADOW_MODEL_MONITORING"
    );
    static final List<String> NOT_INTENDED_USE = List.of(
            "AUTOMATIC_APPROVE_DECLINE_OR_BLOCK",
            "MODEL_PROMOTION_APPROVAL",
            "PAYMENT_AUTHORIZATION",
            "PRIMARY_PRODUCTION_FRAUD_DECISIONING",
            "SCORING_THRESHOLD_CHANGE",
            "WORKFLOW_OR_ANALYST_RECOMMENDATION"
    );
    static final List<String> USE_LIMITATIONS = List.of(
            "ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_GROUND_TRUTH",
            "BOUNDED_RETURNED_POPULATION_ONLY",
            "DOES_NOT_ESTABLISH_GLOBAL_DATASET_COMPLETENESS",
            "DOES_NOT_ESTABLISH_MODEL_CALIBRATION",
            "DOES_NOT_ESTABLISH_PRODUCTION_READINESS",
            "SOURCE_VERIFICATION_LEVEL_MUST_BE_INTERPRETED_EXPLICITLY"
    );

    private ModelCardTestFixtures() {
    }

    static ModelCard validUnavailableCard(ObjectMapper objectMapper) {
        return new ModelCard(
                "ml-model-card-v1",
                "ML_MODEL_CARD_V1",
                GENERATED_AT,
                new ModelCard.Subject(
                        "ML_MODEL",
                        "fraud_logistic",
                        "2026.10.13",
                        "fraud-features-v2",
                        ARTIFACT_SHA,
                        "COMPLETE"
                ),
                "NOT_EVALUATED_FOR_PROMOTION",
                new ModelCard.ModelArtifactVerification("VERIFIED_AGAINST_ARTIFACT_BYTES", ARTIFACT_SHA),
                objectMapper.createObjectNode()
                        .put("status", "NOT_AVAILABLE")
                        .put("reason", "MODEL_EVALUATION_NOT_CONFIGURED"),
                null,
                new ModelCard.VerifiedUsageBoundary(
                        List.of("COMPARE", "OFFLINE_EVALUATION", "SHADOW"),
                        INTENDED_USE,
                        NOT_INTENDED_USE,
                        USE_LIMITATIONS,
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE"
                )
        );
    }

    static ModelCard validAvailableCard(ObjectMapper objectMapper) throws Exception {
        ModelCard unavailable = validUnavailableCard(objectMapper);
        return new ModelCard(
                unavailable.schemaVersion(),
                unavailable.cardType(),
                unavailable.generatedAt(),
                unavailable.subject(),
                unavailable.promotionState(),
                unavailable.modelArtifactVerification(),
                objectMapper.createObjectNode()
                        .put("status", "AVAILABLE")
                        .put("reportType", "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1")
                        .put("artifactSetVersion", "ml-model-feedback-dataset-evaluation-artifact-set-v1")
                        .put("reportGeneratedAt", SUMMARY_GENERATED_AT)
                        .put("manifestSha256", "b".repeat(64))
                        .put("integrityVerification", "VERIFIED_REPORT_AND_MANIFEST")
                        .put("sourceDatasetVerificationLevel", "VERIFIED_AGAINST_SOURCE_BYTES"),
                objectMapper.readTree("""
                        {
                          "reportType":"ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1",
                          "generatedAt":"%s",
                          "sourceDataset":{
                            "datasetVersion":"feedback-dataset-v3",
                            "sha256":"%s",
                            "rawRowsRead":2,
                            "recordsReturned":2,
                            "excludedUnresolvedCount":0,
                            "excludedGovernanceReviewCount":0,
                            "skippedMissingRequiredFieldCount":0,
                            "skippedInvalidSourceRecordCount":0,
                            "truncated":false
                          },
                          "metricBasis":"BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT",
                          "evaluationSubject":{
                            "subjectType":"ML_MODEL",
                            "modelName":"fraud_logistic",
                            "modelVersion":"2026.10.13",
                            "featureContractVersion":"fraud-features-v2",
                            "modelArtifactSha256":"%s",
                            "identityCompleteness":"COMPLETE"
                          },
                          "evaluationWindow":{
                            "timeBasis":"FEEDBACK_CREATED_AT",
                            "fromInclusive":"2026-10-01T00:00:00Z",
                            "toInclusive":"2026-10-12T23:59:59Z"
                          },
                          "population":{
                            "recordsConsidered":2,
                            "recordsWithPredictionEvidence":2,
                            "recordsEvaluated":2,
                            "recordsExcludedIdentityMismatch":0,
                            "recordsExcludedSourceIdentityMismatch":0,
                            "recordsExcludedMissingPredictionEvidence":0,
                            "recordsExcludedUnexpectedMissingPredictionEvidence":0,
                            "recordsExcludedInvalidPredictionEvidence":0
                          },
                          "classBalance":{"positiveClassCount":1,"negativeClassCount":1},
                          "lineagePolicy":{
                            "policy":"REQUESTED_EXACT_ARTIFACT_IDENTITY",
                            "identityMismatchBehavior":"EXCLUDE",
                            "identityMismatchReason":"MODEL_IDENTITY_MISMATCH"
                          },
                          "supportedMetrics":{
                            "classBalance":{"available":true,"reason":null},
                            "mlPredictionMetrics":{
                              "available":true,
                              "reason":null,
                              "classificationPolicy":"RISK_HIGH_OR_CRITICAL_POSITIVE_V1",
                              "rankingPolicy":"EXACT_ML_PREDICTION_SCORE_DESC_EVALUATION_RECORD_ID_ASC_V1",
                              "recordsEvaluated":2,
                              "truePositive":1,
                              "falsePositive":0,
                              "trueNegative":1,
                              "falseNegative":0,
                              "precision":{"available":true,"reason":null,"value":1.0},
                              "recall":{"available":true,"reason":null,"value":1.0},
                              "truePositiveRate":{"available":true,"reason":null,"value":1.0},
                              "falsePositiveRate":{"available":true,"reason":null,"value":0.0},
                              "falseNegativeRate":{"available":true,"reason":null,"value":0.0},
                              "precisionAtK":{
                                "1":{"requestedK":1,"actualK":1,"value":{"available":true,"reason":null,"value":1.0}},
                                "2":{"requestedK":2,"actualK":2,"value":{"available":true,"reason":null,"value":0.5}}
                              },
                              "recallAtK":{
                                "1":{"requestedK":1,"actualK":1,"value":{"available":true,"reason":null,"value":1.0}},
                                "2":{"requestedK":2,"actualK":2,"value":{"available":true,"reason":null,"value":1.0}}
                              }
                            },
                            "rulesVsMlDisagreement":{
                              "available":true,
                              "reason":null,
                              "classificationPolicy":"RISK_HIGH_OR_CRITICAL_POSITIVE_V1",
                              "recordsWithExactMlEvidence":2,
                              "recordsCompared":2,
                              "recordsExcludedRulesEvidenceUnavailable":0,
                              "categoryCounts":{
                                "ML_HIGH_RULES_LOW":0,
                                "RULES_HIGH_ML_LOW":0,
                                "BOTH_HIGH":1,
                                "BOTH_LOW":1
                              }
                            }
                          },
                          "limitations":[
                            "ANALYST_FEEDBACK_LABELS_ARE_REVIEW_SIGNALS",
                            "ML_PREDICTION_METRICS_REQUIRE_DIRECT_ML_OUTPUT_SIGNALS",
                            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION",
                            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_AUTHORIZE_PAYMENTS",
                            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_CHANGE_SCORING"
                          ],
                          "warnings":[]
                        }
                        """.formatted(SUMMARY_GENERATED_AT, "c".repeat(64), ARTIFACT_SHA)),
                unavailable.verifiedUsageBoundary()
        );
    }
}
