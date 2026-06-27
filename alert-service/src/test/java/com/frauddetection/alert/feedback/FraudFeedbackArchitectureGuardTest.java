package com.frauddetection.alert.feedback;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudFeedbackArchitectureGuardTest {

    private static final Path FEEDBACK_MAIN = Path.of("src/main/java/com/frauddetection/alert/feedback");
    private static final Path AUDIT_OUTBOX_MAIN = Path.of("src/main/java/com/frauddetection/alert/audit/outbox");
    private static final List<String> FORBIDDEN_IMPLEMENTATION_TOKENS = List.of(
            "approvePayment",
            "declinePayment",
            "blockTransaction",
            "authorizePayment",
            "automaticDecision",
            "autoApprove",
            "autoDecline",
            "autoBlock",
            "applyRecommendation",
            "acceptRecommendation",
            "rejectRecommendation",
            "promoteModel",
            "deployModel",
            "changeThreshold",
            "thresholdRecommendation",
            "trainModel",
            "retrainModel",
            "exportTrainingDataset",
            "rawMlRequest",
            "rawMlResponse",
            "rawFeatureVector",
            "rawEvidence",
            "groundTruth",
            "trainingLabel",
            "finalDecision",
            "paymentDecision",
            "paymentAuthorization"
    );

    @Test
    void feedbackImplementationDoesNotContainDecisioningOrTrainingBehavior() throws IOException {
        String source = source();

        assertThat(source).doesNotContain(FORBIDDEN_IMPLEMENTATION_TOKENS);
    }

    @Test
    void fraudFeedbackServicePersistsAuditIntentWithoutDirectAuditService() throws IOException {
        String service = Files.readString(FEEDBACK_MAIN.resolve("FraudFeedbackService.java"));

        assertThat(service)
                .contains("WriteActionAuditOutboxService")
                .contains("createPendingAudit")
                .doesNotContain("AuditService", "auditService.audit");
    }

    @Test
    void onlyWriteActionAuditOutboxPublisherCallsAuditServiceInOutboxPackage() throws IOException {
        String publisher = Files.readString(AUDIT_OUTBOX_MAIN.resolve("WriteActionAuditOutboxPublisher.java"));
        String nonPublisherSources = source(AUDIT_OUTBOX_MAIN, path -> !path.getFileName().toString().equals("WriteActionAuditOutboxPublisher.java"));

        assertThat(publisher).contains("AuditService", "auditService.audit");
        assertThat(nonPublisherSources).doesNotContain("AuditService", "auditService.audit");
    }

    @Test
    void feedbackEnumsUseNeutralAnalystReviewNamesOnly() throws IOException {
        String source = source();

        assertThat(source)
                .contains("CONFIRMED_FRAUD", "CONFIRMED_LEGITIMATE", "INCONCLUSIVE", "NEEDS_MORE_INFO")
                .contains("MARKED_FRAUD", "MARKED_LEGITIMATE", "MARKED_INCONCLUSIVE", "REQUESTED_MORE_INFO")
                .contains("ANALYST_REVIEW")
                .doesNotContain("APPROVE_PAYMENT", "DECLINE_PAYMENT", "BLOCK_TRANSACTION", "AUTHORIZE_PAYMENT");
    }

    @Test
    void feedbackDatasetAndEvaluationMisuseRulesStayDocumented() throws IOException {
        String docs = Files.readString(architectureDoc("fraud_feedback_loop.md"));

        assertThat(docs)
                .contains("evaluation candidates")
                .contains("not certified legal ground truth")
                .contains("INCONCLUSIVE")
                .contains("NEEDS_MORE_INFO")
                .contains("must not be used as positive or negative labels")
                .contains("notes are not training input")
                .contains("must not be exported to datasets")
                .contains("bounded signals, not raw evidence");
    }

    @Test
    void feedbackDatasetGovernanceDoesNotAddExportOrPublicApi() throws IOException {
        String governance = source(Path.of("src/main/java/com/frauddetection/alert/feedback/governance"), path -> true);

        assertThat(governance)
                .contains("eligibleForTrainingExport")
                .doesNotContain("@RestController", "@Controller", "@RequestMapping", "export(", "Jsonl", "CSV", "KafkaTemplate");
    }

    @Test
    void writeActionAuditOutboxHasAtomicClaimBoundary() throws IOException {
        String outbox = source(AUDIT_OUTBOX_MAIN, path -> true);

        assertThat(outbox)
                .contains("PUBLISHING")
                .contains("claimForPublishing")
                .contains("findAndModify")
                .contains("ConditionalOnProperty")
                .contains("app.audit.outbox.publisher.fixed-delay-ms");
    }

    @Test
    void fdp122DoesNotExposeOutboxOrDatasetPublicApiOrRuntimeExport() throws IOException {
        String fdp122Main = source(FEEDBACK_MAIN, path -> true) + source(AUDIT_OUTBOX_MAIN, path -> true);

        assertThat(fdp122Main)
                .doesNotContain("WriteActionAuditOutboxController")
                .doesNotContain("FeedbackDatasetController")
                .doesNotContain("FraudFeedbackDatasetController")
                .doesNotContain("exportTrainingDataset")
                .doesNotContain("KafkaTemplate<String, FraudFeedback")
                .doesNotContain("ModelTraining", "ModelPromotion", "ThresholdRecommendation");
    }

    @Test
    void writeActionAuditOutboxDoesNotPersistRawSensitiveMetadata() throws IOException {
        String outbox = source(AUDIT_OUTBOX_MAIN, path -> true);

        assertThat(outbox)
                .contains("WRITE_ACTION_AUDIT_OUTBOX_METADATA_UNSAFE")
                .doesNotContain("notesBody", "rawNotes", "rawMlRequest", "rawMlResponse", "rawFeatureVector", "rawEvidence");
    }

    @Test
    void feedbackGovernanceBlocksDangerousFutureDatasetFields() throws IOException {
        String governanceTest = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/feedback/governance/FeedbackDatasetEligibilityPolicyTest.java"
        ));

        assertThat(governanceTest)
                .contains("rawNotesExport")
                .contains("groundTruth")
                .contains("trainingLabel")
                .contains("finalDecision")
                .contains("paymentDecision")
                .contains("rawMlRequest")
                .contains("feedbackLabel")
                .contains("decisionReasonCodes");
    }

    private String source() throws IOException {
        return source(FEEDBACK_MAIN, path -> true);
    }

    private String source(Path root, java.util.function.Predicate<Path> included) throws IOException {
        StringBuilder source = new StringBuilder();
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(included)
                    .sorted()
                    .forEach(path -> source.append(read(path)).append('\n'));
        }
        return source.toString();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Path architectureDoc(String fileName) {
        Path fromModule = Path.of("..", "docs", "architecture", fileName);
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return Path.of("docs", "architecture", fileName);
    }
}
