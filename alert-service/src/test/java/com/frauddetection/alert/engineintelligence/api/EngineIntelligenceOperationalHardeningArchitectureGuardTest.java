package com.frauddetection.alert.engineintelligence.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceOperationalHardeningArchitectureGuardTest {

    @Test
    void docsStateRetentionPolicy() throws Exception {
        String docs = architectureDoc();

        assertThat(docs)
                .contains("Projection retention")
                .contains("Feedback records follow analyst feedback governance retention")
                .contains("FDP-100 documents retention policy and does not add automatic feedback deletion");
    }

    @Test
    void docsStateAuditRetentionRelationship() throws Exception {
        assertThat(architectureDoc())
                .contains("Audit records may outlive projection and feedback records")
                .contains("Audit retention is separate from projection and feedback retention");
    }

    @Test
    void docsStateNoAutomaticFeedbackDeletionWithoutGovernanceApproval() throws Exception {
        assertThat(architectureDoc())
                .contains("Automatic deletion requires explicit governance approval")
                .contains("does not add automatic feedback deletion");
    }

    @Test
    void docsStateProjectionExpirationBehavior() throws Exception {
        assertThat(architectureDoc())
                .contains("If a projection expires")
                .contains("not-projected");
    }

    @Test
    void docsStateFeedbackOutlivesProjectionBehavior() throws Exception {
        assertThat(architectureDoc())
                .contains("If feedback exists after projection expiry")
                .contains("must not be treated as training labels");
    }

    @Test
    void docsStateIndexStrategy() throws Exception {
        assertThat(architectureDoc())
                .contains("## Index Strategy")
                .contains("collection")
                .contains("access pattern")
                .contains("risk if missing");
    }

    @Test
    void docsStateProjectionIndex() throws Exception {
        assertThat(architectureDoc())
                .contains("engine_intelligence_projections")
                .contains("transactionId as document id / Mongo `_id`");
    }

    @Test
    void docsStateFeedbackIdempotencyIndex() throws Exception {
        assertThat(architectureDoc())
                .contains("engine_intelligence_feedback_idempotency_idx")
                .contains("submittedBy + transactionId + idempotencyKeyHash");
    }

    @Test
    void docsStateFeedbackReadIndex() throws Exception {
        assertThat(architectureDoc())
                .contains("engine_intelligence_feedback_transaction_submitted_feedback_idx")
                .contains("transactionId + submittedAt DESC + feedbackId ASC");
    }

    @Test
    void docsStateRiskIfIndexMissing() throws Exception {
        assertThat(architectureDoc())
                .contains("Projection lookup or read degradation")
                .contains("Duplicate feedback or idempotency break")
                .contains("Expensive transaction-scoped sort");
    }

    @Test
    void docsStateFeedbackReadAttemptIsEndpointLevel() throws Exception {
        assertThat(operationalDocs())
                .contains("feedback_read_attempt_total` is endpoint-level")
                .contains("includes invalid query requests rejected before `service.read(...)`");
    }

    @Test
    void docsStateFeedbackReadLatencyIsEndpointLevel() throws Exception {
        assertThat(operationalDocs())
                .contains("feedback_read_latency_seconds` is endpoint-level")
                .contains("includes query validation, service read, sensitive read audit")
                .contains("bounded failure handling");
    }

    @Test
    void docsStateReadSuccessRequiresAuditSuccess() throws Exception {
        assertThat(operationalDocs())
                .contains("feedback_read_success_total` is recorded only after the read succeeds, sensitive read")
                .contains("feedback_read_empty_total` is recorded only after the read")
                .contains("feedback_read_audit_failure_total")
                .contains("is recorded when sensitive read audit")
                .contains("prevents a successful response");
    }

    @Test
    void docsStateProjectionLatencyIsOncePerAttempt() throws Exception {
        assertThat(operationalDocs())
                .contains("projection_latency_seconds` records exactly one sample per projection attempt")
                .contains("regardless of success, omission, or")
                .contains("failure");
    }

    @Test
    void docsStateSubmitValidationFailureMeaning() throws Exception {
        assertThat(operationalDocs())
                .contains("feedback_submit_validation_failure_total` represents bounded pre-persistence request rejection")
                .contains("malformed input, invalid idempotency, not-found request boundary, or missing authenticated analyst");
    }

    @Test
    void runbookDocumentsRolloutStages() throws Exception {
        assertThat(runbook())
                .contains("Stage 0: default disabled / no user impact")
                .contains("Stage 1: internal sandbox")
                .contains("Stage 2: single environment / test users")
                .contains("Stage 3: limited analyst cohort")
                .contains("Stage 4: broader read-only rollout")
                .contains("Stage 5: governance review surfaces");
    }

    @Test
    void runbookDocumentsRolloutEntryCriteria() throws Exception {
        assertThat(runbook()).contains("Entry criteria");
    }

    @Test
    void runbookDocumentsRolloutExitCriteria() throws Exception {
        assertThat(runbook()).contains("Exit criteria");
    }

    @Test
    void runbookDocumentsRequiredMetrics() throws Exception {
        assertThat(runbook())
                .contains("engine_intelligence_projection_attempt_total")
                .contains("engine_intelligence_feedback_submit_attempt_total")
                .contains("engine_intelligence_feedback_read_attempt_total");
    }

    @Test
    void runbookDocumentsAuditHealthChecks() throws Exception {
        assertThat(runbook())
                .contains("Sensitive read audit healthy")
                .contains("Feedback write audit healthy");
    }

    @Test
    void runbookDocumentsRollbackChecklist() throws Exception {
        assertThat(runbook())
                .contains("Rollback disables or degrades surfaces safely")
                .contains("Disable producer emission flag if needed");
    }

    @Test
    void runbookStatesRollbackDoesNotDeleteAuditRecords() throws Exception {
        assertThat(runbook()).contains("Do not delete audit records");
    }

    @Test
    void runbookStatesRollbackDoesNotDeleteFeedbackWithoutRetentionApproval() throws Exception {
        assertThat(runbook()).contains("Do not delete feedback records without retention approval");
    }

    @Test
    void runbookStatesDoNotBypassReadPolicy() throws Exception {
        assertThat(runbook()).contains("Do not bypass read policy");
    }

    @Test
    void runbookStatesDoNotExposeRawDocuments() throws Exception {
        assertThat(runbook()).contains("Do not expose raw Mongo documents");
    }

    @Test
    void runbookDocumentsRollbackTriggers() throws Exception {
        assertThat(runbook())
                .contains("Projection write failure spike")
                .contains("Feedback read unavailable spike")
                .contains("Idempotency conflict spike");
    }

    @Test
    void runbookDocumentsAlertThresholds() throws Exception {
        assertThat(runbook())
                .contains("projection_failure_rate > threshold for duration")
                .contains("feedback_read_audit_failure > 0")
                .contains("p95/p99 above threshold");
    }

    @Test
    void runbookStatesThresholdsAreInitialAndRequireTuning() throws Exception {
        assertThat(runbook())
                .contains("Initial thresholds are conservative placeholders and must be tuned after rollout observation")
                .contains("not final production SLOs");
    }

    @Test
    void runbookDocumentsStorageGrowthAlerts() throws Exception {
        assertThat(runbook()).contains("Storage growth").contains("growth above baseline");
    }

    @Test
    void runbookDocumentsLatencyAlerts() throws Exception {
        assertThat(runbook()).contains("Latency").contains("p95/p99");
    }

    @Test
    void runbookDocumentsProjectionFailure() throws Exception {
        assertThat(runbook()).contains("Projection store unavailable").contains("projection failures spike");
    }

    @Test
    void runbookDocumentsFeedbackSubmitFailure() throws Exception {
        assertThat(runbook()).contains("Feedback submit validation spike").contains("feedback submit fails");
    }

    @Test
    void runbookDocumentsFeedbackReadFailure() throws Exception {
        assertThat(runbook()).contains("feedback read returns 503");
    }

    @Test
    void runbookDocumentsAuditFailure() throws Exception {
        assertThat(runbook()).contains("Feedback submit audit failure").contains("Feedback read audit failure");
    }

    @Test
    void runbookDocumentsCorruptedStorageFailure() throws Exception {
        assertThat(runbook()).contains("Feedback read corrupted-storage failure").contains("CORRUPTED_STORED_FEEDBACK");
    }

    @Test
    void runbookDocumentsIdempotencyConflictSpike() throws Exception {
        assertThat(runbook()).contains("Idempotency conflict spike");
    }

    @Test
    void runbookDocumentsStorageGrowthSpike() throws Exception {
        assertThat(runbook()).contains("Storage growth spike");
    }

    @Test
    void runbookDocumentsDoNotDoList() throws Exception {
        assertThat(runbook())
                .contains("## Do-Not-Do List")
                .contains("Do not disable audit to make reads work")
                .contains("Do not turn feedback into training labels during rollback");
    }

    @Test
    void architectureIndexIncludesOperationalHardeningDoc() throws Exception {
        assertThat(source("docs/architecture/index.md"))
                .contains("engine_intelligence_operational_hardening.md")
                .contains("FDP-100");
    }

    @Test
    void operationsIndexIncludesEngineIntelligenceRunbook() throws Exception {
        assertThat(source("docs/runbooks/index.md"))
                .contains("engine_intelligence_operational_runbook.md");
        assertThat(source("docs/operations/index.md"))
                .contains("engine intelligence operational runbook");
    }

    @Test
    void apiSurfaceStatesNoNewPublicApiForFdp100() throws Exception {
        assertThat(source("docs/api/api_surface_v1.md"))
                .contains("FDP-100 adds engine-intelligence operational metrics and runbook documentation only")
                .contains("does not add public API");
    }

    @Test
    void securityMapUnchangedForNoNewEndpoint() throws Exception {
        assertThat(source("docs/security/endpoint_authorization_map.md"))
                .doesNotContain("FDP-100")
                .doesNotContain("engine_intelligence_operational");
    }

    @Test
    void operationalHardeningDoesNotCallModelTraining() throws Exception {
        assertOperationalSourcesDoNotContain("modeltraining", "trainingdatasetexport");
    }

    @Test
    void operationalHardeningDoesNotCallRuleUpdates() throws Exception {
        assertOperationalSourcesDoNotContain("ruleupdate", "rule update workflow");
    }

    @Test
    void operationalHardeningDoesNotMutateAlertSeverity() throws Exception {
        assertOperationalSourcesDoNotContain("setseverity", "alertseverity");
    }

    @Test
    void operationalHardeningDoesNotMutateFraudCaseStatus() throws Exception {
        assertOperationalSourcesDoNotContain("setstatus", "fraudcasestatus");
    }

    @Test
    void operationalHardeningDoesNotExposeSubmittedBy() throws Exception {
        String readModel = source(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackReadModel.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackEntryReadModel.java"
        );

        assertThat(readModel).doesNotContain("submittedBy");
    }

    @Test
    void operationalHardeningDoesNotAddGlobalFeedbackSearch() throws Exception {
        assertThat(source("docs/openapi/alert_service.openapi.yaml"))
                .doesNotContain("/api/v1/engine-intelligence/search")
                .doesNotContain("/api/v1/engine-intelligence/feedback");
    }

    @Test
    void operationalHardeningDoesNotAddAnalyticsDashboard() throws Exception {
        assertOperationalSourcesDoNotContain("dashboard", "analyticsdashboard");
    }

    @Test
    void operationalHardeningDoesNotAddFeedbackExport() throws Exception {
        assertOperationalSourcesDoNotContain("feedbackexport", "engineintelligenceexport");
    }

    @Test
    void operationalHardeningDoesNotAddCaseAggregation() throws Exception {
        assertOperationalSourcesDoNotContain("caseaggregation", "fraudcaseaggregation");
    }

    @Test
    void operationalHardeningDoesNotAddPaymentAuthorization() throws Exception {
        assertOperationalSourcesDoNotContain("paymentauthorization");
    }

    @Test
    void operationalHardeningDoesNotAddApproveDeclineBlock() throws Exception {
        assertOperationalSourcesDoNotContain("approvedecline", "declineblock");
    }

    private void assertOperationalSourcesDoNotContain(String... forbiddenTokens) throws Exception {
        String source = sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/observability",
                "alert-service/src/main/java/com/frauddetection/alert/observability/AlertServiceMetrics.java"
        ).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        assertThat(source).doesNotContain(forbiddenTokens);
    }

    private String architectureDoc() throws IOException {
        return source("docs/architecture/engine_intelligence_operational_hardening.md");
    }

    private String runbook() throws IOException {
        return source("docs/runbooks/engine_intelligence_operational_runbook.md");
    }

    private String operationalDocs() throws IOException {
        return architectureDoc() + "\n" + runbook();
    }

    private String source(String... relativePaths) throws IOException {
        StringBuilder content = new StringBuilder();
        for (String relativePath : relativePaths) {
            Path path = repositoryRoot().resolve(relativePath);
            if (Files.isRegularFile(path)) {
                content.append(Files.readString(path)).append('\n');
            }
        }
        return content.toString();
    }

    private String sources(String... relativePaths) throws IOException {
        StringBuilder content = new StringBuilder();
        for (String relativePath : relativePaths) {
            Path path = repositoryRoot().resolve(relativePath);
            if (Files.isRegularFile(path)) {
                content.append(Files.readString(path)).append('\n');
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        content.append(Files.readString(file)).append('\n');
                    }
                }
            }
        }
        return content.toString();
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("alert-service"))
                    && Files.isDirectory(candidate.resolve("common-events"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("REPOSITORY_ROOT_MISSING");
    }
}
