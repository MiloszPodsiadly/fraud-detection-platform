export function modelCard(overrides = {}) {
  const subject = {
    subjectType: "ML_MODEL",
    modelName: "python-logistic-fraud-model",
    modelVersion: "2026-06-25.release",
    featureContractVersion: "feature-contract-v2",
    modelArtifactSha256: "a".repeat(64),
    identityCompleteness: "COMPLETE"
  };
  return {
    schemaVersion: "ml-model-card-v1",
    cardType: "ML_MODEL_CARD_V1",
    generatedAt: "2026-06-10T00:00:01Z",
    subject,
    promotionState: "NOT_EVALUATED_FOR_PROMOTION",
    modelArtifactVerification: { status: "VERIFIED_AGAINST_ARTIFACT_BYTES", sha256: subject.modelArtifactSha256 },
    evaluationEvidence: {
      status: "AVAILABLE",
      reportType: "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1",
      artifactSetVersion: "ml-model-feedback-dataset-evaluation-artifact-set-v1",
      reportGeneratedAt: "2026-06-10T00:00:00Z",
      manifestSha256: "b".repeat(64),
      integrityVerification: "VERIFIED_REPORT_AND_MANIFEST",
      sourceDatasetVerificationLevel: "VERIFIED_AGAINST_SOURCE_BYTES"
    },
    evaluationSummary: evaluationSummary(subject),
    verifiedUsageBoundary: {
      allowedModes: ["COMPARE", "OFFLINE_EVALUATION", "SHADOW"],
      intendedUse: [
        "EXACT_MODEL_OFFLINE_EVALUATION",
        "RULES_VS_ML_DIAGNOSTIC_COMPARISON",
        "SHADOW_MODEL_MONITORING"
      ],
      notIntendedUse: [
        "AUTOMATIC_APPROVE_DECLINE_OR_BLOCK",
        "MODEL_PROMOTION_APPROVAL",
        "PAYMENT_AUTHORIZATION",
        "PRIMARY_PRODUCTION_FRAUD_DECISIONING",
        "SCORING_THRESHOLD_CHANGE",
        "WORKFLOW_OR_ANALYST_RECOMMENDATION"
      ],
      useLimitations: [
        "ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_GROUND_TRUTH",
        "BOUNDED_RETURNED_POPULATION_ONLY",
        "DOES_NOT_ESTABLISH_GLOBAL_DATASET_COMPLETENESS",
        "DOES_NOT_ESTABLISH_MODEL_CALIBRATION",
        "DOES_NOT_ESTABLISH_PRODUCTION_READINESS",
        "SOURCE_VERIFICATION_LEVEL_MUST_BE_INTERPRETED_EXPLICITLY"
      ],
      runtimeDecisionAuthority: "NONE",
      promotionAuthority: "NONE",
      thresholdChangeAuthority: "NONE",
      paymentAuthorizationAuthority: "NONE",
      workflowAuthority: "NONE"
    },
    ...overrides
  };
}

export function modelCardWithoutEvaluation() {
  return modelCard({
    evaluationEvidence: { status: "NOT_AVAILABLE", reason: "MODEL_EVALUATION_NOT_CONFIGURED" },
    evaluationSummary: null
  });
}

function evaluationSummary(subject) {
  return {
    reportType: "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1",
    generatedAt: "2026-06-10T00:00:00Z",
    sourceDataset: {
      datasetVersion: "feedback-dataset-v3",
      sha256: "c".repeat(64),
      rawRowsRead: 2,
      recordsReturned: 2,
      excludedUnresolvedCount: 0,
      excludedGovernanceReviewCount: 0,
      skippedMissingRequiredFieldCount: 0,
      skippedInvalidSourceRecordCount: 0,
      truncated: false
    },
    metricBasis: "BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT",
    evaluationSubject: { ...subject },
    evaluationWindow: {
      timeBasis: "FEEDBACK_CREATED_AT",
      fromInclusive: "2026-06-01T00:00:00Z",
      toInclusive: "2026-06-09T00:00:00Z"
    },
    population: {
      recordsConsidered: 2,
      recordsWithPredictionEvidence: 2,
      recordsEvaluated: 2,
      recordsExcludedIdentityMismatch: 0,
      recordsExcludedSourceIdentityMismatch: 0,
      recordsExcludedMissingPredictionEvidence: 0,
      recordsExcludedUnexpectedMissingPredictionEvidence: 0,
      recordsExcludedInvalidPredictionEvidence: 0
    },
    classBalance: { positiveClassCount: 1, negativeClassCount: 1 },
    lineagePolicy: {
      policy: "REQUESTED_EXACT_ARTIFACT_IDENTITY",
      identityMismatchBehavior: "EXCLUDE",
      identityMismatchReason: "MODEL_IDENTITY_MISMATCH"
    },
    supportedMetrics: {
      classBalance: { available: true, reason: null },
      mlPredictionMetrics: {
        available: true,
        reason: null,
        classificationPolicy: "RISK_HIGH_OR_CRITICAL_POSITIVE_V1",
        rankingPolicy: "EXACT_ML_PREDICTION_SCORE_DESC_EVALUATION_RECORD_ID_ASC_V1",
        recordsEvaluated: 2,
        truePositive: 1,
        falsePositive: 0,
        trueNegative: 1,
        falseNegative: 0,
        precision: metric(1),
        recall: metric(1),
        truePositiveRate: metric(1),
        falsePositiveRate: metric(0),
        falseNegativeRate: metric(0),
        precisionAtK: rankingMetrics(0.5),
        recallAtK: rankingMetrics(1)
      },
      rulesVsMlDisagreement: {
        available: true,
        reason: null,
        classificationPolicy: "RISK_HIGH_OR_CRITICAL_POSITIVE_V1",
        recordsWithExactMlEvidence: 2,
        recordsCompared: 2,
        recordsExcludedRulesEvidenceUnavailable: 0,
        categoryCounts: { ML_HIGH_RULES_LOW: 0, RULES_HIGH_ML_LOW: 0, BOTH_HIGH: 1, BOTH_LOW: 1 }
      }
    },
    limitations: [
      "ANALYST_FEEDBACK_LABELS_ARE_REVIEW_SIGNALS",
      "ML_PREDICTION_METRICS_REQUIRE_DIRECT_ML_OUTPUT_SIGNALS",
      "MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION",
      "MODEL_SPECIFIC_EVALUATION_DOES_NOT_AUTHORIZE_PAYMENTS",
      "MODEL_SPECIFIC_EVALUATION_DOES_NOT_CHANGE_SCORING"
    ],
    warnings: []
  };
}

function metric(value) {
  return { available: true, reason: null, value };
}

function rankingMetrics(valueAtTwo) {
  return {
    1: { requestedK: 1, actualK: 1, value: metric(1) },
    2: { requestedK: 2, actualK: 2, value: metric(valueAtTwo) }
  };
}
