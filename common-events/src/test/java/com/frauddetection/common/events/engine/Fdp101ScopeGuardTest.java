package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
                .doesNotContain("featurevector")
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
    }

    private List<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
