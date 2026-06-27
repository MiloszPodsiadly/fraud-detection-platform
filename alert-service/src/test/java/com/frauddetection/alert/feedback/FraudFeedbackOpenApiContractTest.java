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
                .contains("\"503\":")
                .contains("Feedback could not be safely completed because durable write-action audit outbox intent could not be persisted")
                .contains("FRAUD_FEEDBACK_AUDIT_OUTBOX_UNAVAILABLE")
                .contains("A 201 response means the feedback record and write-action audit outbox intent are durable")
                .contains("audit publication may occur later")
                .doesNotContain("201 response means AuditService publication is complete")
                .contains("This feedback is an analyst review outcome and future evaluation signal only")
                .contains("It is not certified legal ground truth")
                .contains("FraudFeedbackLabel:")
                .contains("enum: [CONFIRMED_FRAUD, CONFIRMED_LEGITIMATE, INCONCLUSIVE, NEEDS_MORE_INFO]")
                .contains("AnalystDecision:")
                .contains("enum: [MARKED_FRAUD, MARKED_LEGITIMATE, MARKED_INCONCLUSIVE, REQUESTED_MORE_INFO]")
                .contains("FeedbackLabelSource:")
                .contains("enum: [ANALYST_REVIEW]")
                .contains("FraudFeedbackStatus:")
                .contains("enum: [RECORDED]")
                .contains("FraudFeedbackReasonCode:")
                .contains("CUSTOMER_CONFIRMED_FRAUD")
                .contains("ANALYST_CONFIRMED_FRAUD")
                .contains("UNKNOWN_REASON")
                .contains("CONFIRMED_FRAUD requires MARKED_FRAUD")
                .contains("CONFIRMED_LEGITIMATE requires MARKED_LEGITIMATE")
                .contains("INCONCLUSIVE requires MARKED_INCONCLUSIVE")
                .contains("NEEDS_MORE_INFO requires REQUESTED_MORE_INFO")
                .contains("required: [analystDecision, feedbackLabel, decisionReasonCodes]")
                .contains("Reason codes must come from FraudFeedbackReasonCode and must be compatible with feedbackLabel")
                .contains("CONFIRMED_FRAUD reason codes: CUSTOMER_CONFIRMED_FRAUD")
                .contains("CONFIRMED_LEGITIMATE reason codes: CUSTOMER_CONFIRMED_LEGITIMATE")
                .contains("INCONCLUSIVE reason codes: INSUFFICIENT_EVIDENCE")
                .contains("NEEDS_MORE_INFO reason codes: NEEDS_CUSTOMER_CONTACT")
                .contains("$ref: \"#/components/schemas/FraudFeedbackReasonCode\"")
                .contains("notesPresent")
                .contains("does not expose raw notes")
                .contains("Bounded analyst feedback record and write-action audit intent created");
    }

    @Test
    void openApiDocumentsValidationBoundsAndNonDecisioningBoundary() throws Exception {
        String openApi = Files.readString(openApiPath());

        assertThat(openApi)
                .contains("maxItems: 10")
                .contains("minItems: 1")
                .contains("maxLength: 500")
                .contains("At least one reason code is required")
                .contains("FRAUD_FEEDBACK_REASON_CODES_REQUIRED")
                .contains("decision/label mismatch")
                .contains("unknown reason code")
                .contains("FRAUD_FEEDBACK_REASON_CODE_LABEL_MISMATCH")
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
