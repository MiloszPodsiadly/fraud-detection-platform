package com.frauddetection.alert.feedback;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudFeedbackOpenApiContractTest {

    @Test
    void openApiDocumentsBoundedFraudFeedbackContract() throws Exception {
        String openApi = Files.readString(openApiPath());

        assertThat(openApi)
                .contains("/api/v1/transactions/scored/{transactionId}/feedback:")
                .contains("fraud-feedback:write")
                .contains("fraud-feedback:read")
                .contains("$ref: \"#/components/schemas/CreateFraudFeedbackRequest\"")
                .contains("$ref: \"#/components/schemas/FraudFeedbackResponse\"")
                .contains("\"409\":")
                .contains("Duplicate POST for a transaction with existing active feedback returns 409")
                .contains("This feedback is an analyst review outcome and future evaluation signal only")
                .contains("It is not certified legal ground truth")
                .contains("FraudFeedbackLabel:")
                .contains("enum: [CONFIRMED_FRAUD, CONFIRMED_LEGITIMATE, INCONCLUSIVE, NEEDS_MORE_INFO]")
                .contains("AnalystDecision:")
                .contains("enum: [MARKED_FRAUD, MARKED_LEGITIMATE, MARKED_INCONCLUSIVE, REQUESTED_MORE_INFO]")
                .contains("FeedbackLabelSource:")
                .contains("enum: [ANALYST_REVIEW]")
                .contains("FraudFeedbackStatus:")
                .contains("enum: [RECORDED]");
    }

    @Test
    void openApiDocumentsValidationBoundsAndNonDecisioningBoundary() throws Exception {
        String openApi = Files.readString(openApiPath());

        assertThat(openApi)
                .contains("maxItems: 10")
                .contains("pattern: \"^[A-Z0-9_]+$\"")
                .contains("maxLength: 500")
                .contains("does not mutate scoring, recommendation, payment, workflow, case, model, threshold, or dataset behavior")
                .doesNotContain("APPROVE_PAYMENT", "DECLINE_PAYMENT", "BLOCK_TRANSACTION", "AUTHORIZE_PAYMENT");
    }

    private Path openApiPath() {
        Path fromRoot = Path.of("docs", "openapi", "alert_service.openapi.yaml");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "docs", "openapi", "alert_service.openapi.yaml");
    }
}
