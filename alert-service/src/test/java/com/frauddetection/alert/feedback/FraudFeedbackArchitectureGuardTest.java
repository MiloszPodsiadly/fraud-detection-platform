package com.frauddetection.alert.feedback;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudFeedbackArchitectureGuardTest {

    private static final Path FEEDBACK_MAIN = Path.of("src/main/java/com/frauddetection/alert/feedback");
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
    void feedbackEnumsUseNeutralAnalystReviewNamesOnly() throws IOException {
        String source = source();

        assertThat(source)
                .contains("CONFIRMED_FRAUD", "CONFIRMED_LEGITIMATE", "INCONCLUSIVE", "NEEDS_MORE_INFO")
                .contains("MARKED_FRAUD", "MARKED_LEGITIMATE", "MARKED_INCONCLUSIVE", "REQUESTED_MORE_INFO")
                .contains("ANALYST_REVIEW")
                .doesNotContain("APPROVE_PAYMENT", "DECLINE_PAYMENT", "BLOCK_TRANSACTION", "AUTHORIZE_PAYMENT");
    }

    private String source() throws IOException {
        StringBuilder source = new StringBuilder();
        try (var files = Files.walk(FEEDBACK_MAIN)) {
            files.filter(path -> path.toString().endsWith(".java"))
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
}
