package com.frauddetection.alert.governance;

public final class PlatformEvaluationArtifactIdentityPolicy {

    public static final String CURRENT_REPORT_TYPE = "FEEDBACK_DATASET_OFFLINE_EVALUATION_V1";
    public static final String CURRENT_ARTIFACT_SET_VERSION = "feedback-dataset-evaluation-report-artifact-set-v1";
    public static final String LEGACY_READ_ONLY_REPORT_TYPE = "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1";
    public static final String LEGACY_READ_ONLY_ARTIFACT_SET_VERSION = "fdp123-report-artifact-set-v1";
    public static final String CURRENT_IDENTITY_COMPLETENESS =
            "NO_MODEL_ARTIFACT_IDENTITY_IN_FEEDBACK_DATASET_SOURCE";
    public static final String LEGACY_READ_ONLY_IDENTITY_COMPLETENESS =
            "NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE";

    private PlatformEvaluationArtifactIdentityPolicy() {
    }

    public static boolean isSupportedReadIdentity(String reportType, String artifactSetVersion) {
        return isCurrentIdentity(reportType, artifactSetVersion) || isLegacyReadOnlyIdentity(reportType, artifactSetVersion);
    }

    public static boolean isSupportedReadReportType(String reportType) {
        return CURRENT_REPORT_TYPE.equals(reportType) || LEGACY_READ_ONLY_REPORT_TYPE.equals(reportType);
    }

    public static boolean isCurrentIdentity(String reportType, String artifactSetVersion) {
        return CURRENT_REPORT_TYPE.equals(reportType) && CURRENT_ARTIFACT_SET_VERSION.equals(artifactSetVersion);
    }

    public static boolean isLegacyReadOnlyIdentity(String reportType, String artifactSetVersion) {
        return LEGACY_READ_ONLY_REPORT_TYPE.equals(reportType)
                && LEGACY_READ_ONLY_ARTIFACT_SET_VERSION.equals(artifactSetVersion);
    }

    public static boolean isSupportedReadProvenance(
            String reportType,
            String artifactSetVersion,
            String identityCompleteness
    ) {
        return (isCurrentIdentity(reportType, artifactSetVersion)
                && CURRENT_IDENTITY_COMPLETENESS.equals(identityCompleteness))
                || (isLegacyReadOnlyIdentity(reportType, artifactSetVersion)
                && LEGACY_READ_ONLY_IDENTITY_COMPLETENESS.equals(identityCompleteness));
    }
}
