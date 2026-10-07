package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.config.AlertKafkaConfig;
import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.messaging.TransactionScoredEventListener;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalTransactionScoredEventDeserializationTest {

    private final AlertKafkaConfig config = new AlertKafkaConfig();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void canonicalDeserializerPreservesExactEvidence() throws Exception {
        TransactionScoredEvent source = MlPredictionEvidenceProjectionTestSupport.event("event-valid", 0.8123d, "v1");
        byte[] payload = objectMapper.writeValueAsBytes(source);
        String fingerprint = ScoringOccurrenceFingerprint.from(source);

        RecordHeaders headers = new RecordHeaders();
        TransactionScoredEvent deserialized = canonicalDeserializer()
                .deserialize("transactions.scored", headers, payload);

        assertThat(headers).isEmpty();
        assertThat(deserialized).isEqualTo(source);
        assertThat(deserialized.mlPredictionEvidence()).isEqualTo(source.mlPredictionEvidence());
        assertThat(deserialized.mlPredictionEvidenceOmissionReason()).isNull();
        assertThat(deserialized.eventId()).isEqualTo(source.eventId());
        assertThat(deserialized.createdAt()).isEqualTo(source.createdAt());
        assertThat(ScoringOccurrenceFingerprint.from(deserialized)).isEqualTo(fingerprint);
    }

    @Test
    void canonicalDeserializerPreservesExplicitOmission() throws Exception {
        TransactionScoredEvent source = MlPredictionEvidenceProjectionTestSupport.eventWithoutEvidence("event-omitted");
        byte[] payload = objectMapper.writeValueAsBytes(source);

        RecordHeaders headers = new RecordHeaders();
        TransactionScoredEvent deserialized = canonicalDeserializer()
                .deserialize("transactions.scored", headers, payload);

        assertThat(headers).isEmpty();
        assertThat(deserialized).isEqualTo(source);
        assertThat(deserialized.mlPredictionEvidence()).isNull();
        assertThat(deserialized.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED);
    }

    @Test
    void canonicalDeserializerRejectsMalformedEvidence() throws Exception {
        ObjectNode evidenceAndOmission = validEventJson("event-both-outcomes");
        evidenceAndOmission.put(
                "mlPredictionEvidenceOmissionReason",
                MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED.name()
        );

        ObjectNode partialIdentity = validEventJson("event-partial-identity");
        ((ObjectNode) partialIdentity.path("mlPredictionEvidence")).remove("modelVersion");

        ObjectNode unsupportedVersion = validEventJson("event-version");
        ((ObjectNode) unsupportedVersion.path("mlPredictionEvidence")).put("contractVersion", 2);

        for (ObjectNode malformed : List.of(evidenceAndOmission, partialIdentity, unsupportedVersion)) {
            byte[] payload = objectMapper.writeValueAsBytes(malformed);
            RecordHeaders headers = new RecordHeaders();

            assertThat(canonicalDeserializer().deserialize("transactions.scored", headers, payload)).isNull();
            assertThat(headers).isNotEmpty();
        }
    }

    @Test
    void canonicalDeserializerRejectsMalformedJson() {
        byte[] payload = "{not-json".getBytes(StandardCharsets.UTF_8);

        RecordHeaders headers = new RecordHeaders();

        assertThat(canonicalDeserializer().deserialize("transactions.scored", headers, payload)).isNull();
        assertThat(headers).isNotEmpty();
    }

    @Test
    void baselineBusinessProcessingReceivesCanonicalEventWithEvidenceIntact() throws Exception {
        TransactionScoredEvent source = MlPredictionEvidenceProjectionTestSupport.event(
                "event-baseline-processing",
                0.8123d,
                "v1"
        );
        TransactionScoredEvent deserialized = canonicalDeserializer().deserialize(
                "transactions.scored",
                new RecordHeaders(),
                objectMapper.writeValueAsBytes(source)
        );
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
        when(transactionMonitoring.recordScoredTransaction(deserialized)).thenReturn(new ScoringOccurrenceAdmissionResult(
                ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW,
                ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED
        ));

        listener.onMessage(deserialized, "trace-baseline-processing");

        assertThat(deserialized.mlPredictionEvidence()).isEqualTo(source.mlPredictionEvidence());
        verify(transactionMonitoring).recordScoredTransaction(same(deserialized));
        verify(alertManagement).handleScoredTransaction(same(deserialized));
    }

    private ObjectNode validEventJson(String eventId) {
        return objectMapper.valueToTree(MlPredictionEvidenceProjectionTestSupport.event(eventId, 0.8123d, "v1"));
    }

    @SuppressWarnings("unchecked")
    private Deserializer<TransactionScoredEvent> canonicalDeserializer() {
        return ((DefaultKafkaConsumerFactory<String, TransactionScoredEvent>) config
                .transactionScoredEventConsumerFactory(new KafkaProperties())).getValueDeserializer();
    }
}
