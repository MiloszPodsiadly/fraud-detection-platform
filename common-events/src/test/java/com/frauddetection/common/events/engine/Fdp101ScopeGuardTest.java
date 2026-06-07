package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp101ScopeGuardTest {

    @Test
    void fraudEngineResultStaysBoundedAndDiagnosticOnly() {
        assertThat(FraudEngineResult.REASON_CODES_MAX_SIZE).isEqualTo(10);
        assertThat(FraudEngineResult.CONTRIBUTIONS_MAX_SIZE).isEqualTo(10);
        assertThat(FraudEngineResult.EVIDENCE_MAX_SIZE).isEqualTo(10);
        assertThat(FraudEngineResult.LATENCY_MS_MAX).isEqualTo(300_000);

        assertThat(recordComponentNames(FraudEngineResult.class))
                .contains("engineId", "engineType", "engineLanguage", "status", "score", "riskLevel",
                        "confidence", "reasonCodes", "contributions", "evidence", "latencyMs", "modelName",
                        "modelVersion", "statusReason", "generatedAt")
                .doesNotContain("engineResults", "platformRiskScore", "engineAgreement", "finalDecision",
                        "recommendedAction", "paymentAuthorization", "groundTruth", "modelTrainingLabel");

        assertThat(recordComponentTypes(FraudEngineResult.class))
                .doesNotContain(Map.class, Object.class)
                .noneMatch(type -> type.getSimpleName().equals("JsonNode"));
    }

    @Test
    void transactionScoredEventIsNotExtendedWithEngineResults() {
        assertThat(recordComponentNames(TransactionScoredEvent.class))
                .doesNotContain("engineResults", "fraudEngineResults", "fraudEngineResult");
    }

    @Test
    void publicEngineContractStringsDoNotDeclareDecisioningSemantics() {
        String publicNames = String.join(" ", recordComponentNames(FraudEngineResult.class))
                + " " + String.join(" ", recordComponentNames(FraudEngineContribution.class))
                + " " + String.join(" ", recordComponentNames(FraudEngineEvidence.class));
        String compact = publicNames.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        assertThat(compact)
                .doesNotContain("rawpayload")
                .doesNotContain("rawrequest")
                .doesNotContain("rawresponse")
                .doesNotContain("rawevidence")
                .doesNotContain("rawcontribution")
                .doesNotContain("featurevector")
                .doesNotContain("exceptionmessage")
                .doesNotContain("stacktrace")
                .doesNotContain("token")
                .doesNotContain("secret")
                .doesNotContain("endpoint")
                .doesNotContain("metadata")
                .doesNotContain("customerid")
                .doesNotContain("accountid")
                .doesNotContain("cardid")
                .doesNotContain("deviceid")
                .doesNotContain("merchantid")
                .doesNotContain("finaldecision")
                .doesNotContain("recommendedaction")
                .doesNotContain("paymentauthorization")
                .doesNotContain("groundtruth")
                .doesNotContain("modeltraininglabel");

        assertThat(recordComponentTypes(FraudEngineResult.class))
                .doesNotContain(Map.class, Object.class)
                .noneMatch(type -> type.getSimpleName().equals("JsonNode"));
        assertThat(recordComponentTypes(FraudEngineContribution.class))
                .doesNotContain(Map.class, Object.class)
                .noneMatch(type -> type.getSimpleName().equals("JsonNode"));
        assertThat(recordComponentTypes(FraudEngineEvidence.class))
                .doesNotContain(Map.class, Object.class)
                .noneMatch(type -> type.getSimpleName().equals("JsonNode"));
    }

    private List<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private List<Class<?>> recordComponentTypes(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
    }
}
