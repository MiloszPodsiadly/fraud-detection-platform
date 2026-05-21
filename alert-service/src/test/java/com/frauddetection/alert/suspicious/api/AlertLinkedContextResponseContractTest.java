package com.frauddetection.alert.suspicious.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertLinkedContextResponseContractTest {

    @Test
    void LinkedAlertContextStateDoesNotContainAccessDeniedTest() {
        assertThat(LinkedAlertContextState.values())
                .extracting(Enum::name)
                .doesNotContain("ACCESS_DENIED", "CONFIRMED_FRAUD", "ANALYST_DECISION");
    }

    @Test
    void AlertLinkedContextResponseDoesNotExposeDecisionFieldsTest() {
        assertThat(fieldNames()).doesNotContain(
                "analystDecision",
                "analystId",
                "decidedAt",
                "decisionReason",
                "decisionTags",
                "decisionIdempotencyKey",
                "decisionIdempotencyRequestHash",
                "idempotencyKey"
        );
    }

    @Test
    void AlertLinkedContextResponseDoesNotExposeCaseLifecycleFieldsTest() {
        assertThat(fieldNames()).doesNotContain(
                "caseId",
                "caseStatus",
                "caseDecision",
                "finalOutcome",
                "workflowActions",
                "decisionActions",
                "recommendedAction"
        );
    }

    @Test
    void AlertLinkedContextResponseDoesNotExposeEvidenceProofFieldsTest() {
        assertThat(fieldNames()).doesNotContain(
                "evidenceSnapshot",
                "legalProof",
                "evidenceProof",
                "assistantSummary"
        );
    }

    @Test
    void AlertLinkedContextResponseDoesNotExposeRawPayloadFieldsTest() {
        assertThat(fieldNames()).doesNotContain(
                "rawPayload",
                "scoreDetails",
                "featureSnapshot",
                "transactionAmount",
                "merchantInfo",
                "deviceInfo",
                "locationInfo",
                "customerContext"
        );
    }

    @Test
    void AlertLinkedContextResponseUnavailableStatesDoNotContainAlertFieldsTest() {
        List<AlertLinkedContextResponse> unavailableResponses = List.of(
                AlertLinkedContextResponse.noLinkedAlert(),
                AlertLinkedContextResponse.linkedAlertNotFound(),
                AlertLinkedContextResponse.relationshipMismatch(),
                AlertLinkedContextResponse.temporarilyUnavailable()
        );

        assertThat(unavailableResponses).allSatisfy(response -> {
            assertThat(response.alertId()).isNull();
            assertThat(response.transactionId()).isNull();
            assertThat(response.customerId()).isNull();
            assertThat(response.accountId()).isNull();
            assertThat(response.alertScore()).isNull();
            assertThat(response.riskLevel()).isNull();
            assertThat(response.alertStatus()).isNull();
            assertThat(response.reasonCodes()).isEmpty();
            assertThat(response.createdAt()).isNull();
            assertThat(response.updatedAt()).isNull();
            assertThat(response.correlationId()).isNull();
            assertThat(response.scoreDecisionId()).isNull();
        });
    }

    @Test
    void availableJsonContainsOnlyApprovedFields() throws Exception {
        AlertLinkedContextResponse response = AlertLinkedContextResponse.available(
                "alert-1",
                "transaction-1",
                "customer-1",
                "account-1",
                0.94,
                RiskLevel.CRITICAL,
                AlertStatus.OPEN,
                List.of("HIGH_AMOUNT"),
                Instant.parse("2026-05-20T10:00:00Z"),
                null,
                "correlation-1",
                "score-decision-1"
        );

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(json)
                .contains("alertId", "transactionId", "customerId", "accountId", "alertScore")
                .doesNotContain("analystDecision", "caseId", "evidenceSnapshot", "rawPayload", "idempotency");
    }

    @Test
    void availableResponseAllowsNullUpdatedAt() {
        AlertLinkedContextResponse response = AlertLinkedContextResponse.available(
                "alert-1",
                "transaction-1",
                "customer-1",
                "account-1",
                0.94,
                RiskLevel.CRITICAL,
                AlertStatus.OPEN,
                List.of("HIGH_AMOUNT"),
                Instant.parse("2026-05-20T10:00:00Z"),
                null,
                "correlation-1",
                "score-decision-1"
        );

        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-05-20T10:00:00Z"));
        assertThat(response.updatedAt()).isNull();
    }

    private List<String> fieldNames() {
        return Arrays.stream(AlertLinkedContextResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
