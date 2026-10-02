package com.frauddetection.alert.governance;

public final class PlatformEvaluationArtifactIdentityPolicy {

    public static final String CURRENT_REPORT_TYPE = "FEEDBACK_DATASET_OFFLINE_EVALUATION_V1";
    public static final String CURRENT_ARTIFACT_SET_VERSION = "feedback-dataset-evaluation-report-artifact-set-v1";
    public static final String CURRENT_IDENTITY_COMPLETENESS =
            "NO_MODEL_ARTIFACT_IDENTITY_IN_FEEDBACK_DATASET_SOURCE";

    private PlatformEvaluationArtifactIdentityPolicy() {
    }

    public static boolean isCanonicalIdentity(String reportType, String artifactSetVersion) {
        return CURRENT_REPORT_TYPE.equals(reportType) && CURRENT_ARTIFACT_SET_VERSION.equals(artifactSetVersion);
    }

    public static boolean isCanonicalReportType(String reportType) {
        return CURRENT_REPORT_TYPE.equals(reportType);
    }

    public static boolean isCanonicalProvenance(
            String reportType,
            String artifactSetVersion,
            String identityCompleteness
    ) {
        return isCanonicalIdentity(reportType, artifactSetVersion)
                && CURRENT_IDENTITY_COMPLETENESS.equals(identityCompleteness);
    }
}
