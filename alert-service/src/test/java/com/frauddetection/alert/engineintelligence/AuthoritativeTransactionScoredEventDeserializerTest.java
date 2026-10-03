package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.messaging.AuthoritativeTransactionScoredEventDeserializer;
import com.frauddetection.alert.messaging.TransactionScoredEventListener;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthoritativeTransactionScoredEventDeserializerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final AuthoritativeTransactionScoredEventDeserializer deserializer =
            new AuthoritativeTransactionScoredEventDeserializer();

    @Test
    void validEvidenceIsExcludedFromAuthoritativeBaselineView() throws Exception {
        TransactionScoredEvent source = MlPredictionEvidenceProjectionTestSupport.event("event-valid", 0.8123d, "v1");

        TransactionScoredEvent baseline = deserialize(objectMapper.valueToTree(source));

        assertThat(baseline.transactionId()).isEqualTo(source.transactionId());
        assertThat(baseline.fraudScore()).isEqualTo(source.fraudScore());
        assertThat(baseline.riskLevel()).isEqualTo(source.riskLevel());
        assertThat(baseline.alertRecommended()).isEqualTo(source.alertRecommended());
        assertThat(baseline.engineIntelligence()).isEqualTo(source.engineIntelligence());
        assertThat(baseline.mlPredictionEvidence()).isNull();
    }

    @Test
    void invalidOptionalEvidenceDoesNotRejectValidAuthoritativeBaseline() throws Exception {
        ObjectNode unsupportedVersion = validEventJson("event-version");
        ((ObjectNode) unsupportedVersion.path("mlPredictionEvidence")).put("contractVersion", 2);

        ObjectNode partialIdentity = validEventJson("event-partial-identity");
        ((ObjectNode) partialIdentity.path("mlPredictionEvidence")).remove("modelVersion");

        ObjectNode incompatibleScoreBucket = validEventJson("event-score-bucket");
        ((ObjectNode) incompatibleScoreBucket.path("mlPredictionEvidence")).put("mlScore", 0.2d);

        ObjectNode malformedEvidenceShape = validEventJson("event-malformed-evidence");
        malformedEvidenceShape.put("mlPredictionEvidence", "not-an-evidence-object");

        for (ObjectNode json : new ObjectNode[]{
                unsupportedVersion,
                partialIdentity,
                incompatibleScoreBucket,
                malformedEvidenceShape
        }) {
            TransactionScoredEvent baseline = deserialize(json);

            assertThat(baseline.transactionId()).isEqualTo("txn-evidence-1");
            assertThat(baseline.mlPredictionEvidence()).isNull();
        }
    }

    @Test
    void absentEvidenceInOlderEventRemainsAccepted() throws Exception {
        TransactionScoredEvent source = MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence("event-old");

        assertThat(deserialize(objectMapper.valueToTree(source)).mlPredictionEvidence()).isNull();
    }

    @Test
    void baselineAlertLifecycleProcessesEventWithRejectedOptionalEvidence() throws Exception {
        ObjectNode json = validEventJson("event-baseline-processing");
        ((ObjectNode) json.path("mlPredictionEvidence")).put("contractVersion", 2);
        TransactionScoredEvent baseline = deserialize(json);
        AlertManagementUseCase alertManagement = mock(AlertManagementUseCase.class);
        TransactionMonitoringUseCase transactionMonitoring = mock(TransactionMonitoringUseCase.class);
        TransactionScoredEventListener listener = new TransactionScoredEventListener(
                alertManagement,
                transactionMonitoring,
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );

        listener.onMessage(baseline, "trace-baseline-processing");

        verify(transactionMonitoring).recordScoredTransaction(same(baseline));
        verify(alertManagement).handleScoredTransaction(same(baseline));
    }

    @Test
    void invalidAuthoritativeFieldAndMalformedJsonStillFailClosed() throws Exception {
        ObjectNode invalidBaseline = validEventJson("event-invalid-baseline");
        invalidBaseline.put("riskLevel", "NOT_A_RISK_LEVEL");

        assertThatThrownBy(() -> deserialize(invalidBaseline)).isInstanceOf(SerializationException.class);
        assertThatThrownBy(() -> deserializer.deserialize(
                "transactions.scored",
                "{not-json".getBytes(StandardCharsets.UTF_8)
        )).isInstanceOf(SerializationException.class);
    }

    private ObjectNode validEventJson(String eventId) {
        return objectMapper.valueToTree(MlPredictionEvidenceProjectionTestSupport.event(eventId, 0.8123d, "v1"));
    }

    private TransactionScoredEvent deserialize(ObjectNode json) throws Exception {
        return deserializer.deserialize(
                "transactions.scored",
                objectMapper.writeValueAsBytes(json)
        );
    }
}
