package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionScoredEngineIntelligenceByteBoundaryTest {
    private final JacksonKafkaDeserializer<TransactionScoredEvent> deserializer =
            new JacksonKafkaDeserializer<>(TransactionScoredEvent.class);

    @Test
    void escapedFieldNameUsesExactUtf8ByteBoundary() {
        int maximum = 16_384;
        assertThatThrownBy(() -> deserializeWithNestedValueBytes(maximum, true))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
        assertThatThrownBy(() -> deserializeWithNestedValueBytes(maximum + 1, false))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_PAYLOAD_BYTES_INVALID");
    }

    @Test
    void duplicateDecodedEngineIntelligenceNamesAreRejectedWithStableWireReasonCode() {
        String json = "{\"engineIntelligence\":null,\"engine\\u0049ntelligence\":null}";

        assertThatThrownBy(() -> deserializer.deserialize("transactions.scored", utf8(json)))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_JSON_INVALID");
    }

    @Test
    void duplicateTopLevelEventContractVersionIsRejectedAtTheRawWireBoundary() throws Exception {
        String json = fullFixture().replace(
                "\"eventContractVersion\": 2,",
                "\"eventContractVersion\": 1,\n  \"eventContractVersion\": 2,"
        );

        assertThatThrownBy(() -> deserializer.deserialize("transactions.scored", utf8(json)))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_JSON_INVALID");
    }

    @Test
    void duplicateTopLevelFieldsAreRejectedBeforePojoDeserialization() throws Exception {
        assertStableInvalid(fullFixture().replace(
                "\"transactionId\": \"txn-engine-intelligence-contract-001\",",
                "\"transactionId\": \"first\",\n  \"transactionId\": \"txn-engine-intelligence-contract-001\","));
        assertStableInvalid(fullFixture().replace(
                "\"mlPredictionEvidenceOmissionReason\": \"ML_ENGINE_UNAVAILABLE\"",
                "\"mlPredictionEvidenceOmissionReason\": \"first\",\n"
                        + "  \"mlPredictionEvidenceOmissionReason\": \"ML_ENGINE_UNAVAILABLE\""));
        assertStableInvalid(fullFixture().replace(
                "\"engineIntelligence\": {",
                "\"engineIntelligence\": null,\n  \"engineIntelligence\": {"));
        assertStableInvalid(fullFixture().replace(
                "\n}",
                ",\n  \"mlPredictionEvidence\": null,\n  \"mlPredictionEvidence\": null\n}"));
    }

    @Test
    void nonUtf8TransactionScoredPayloadIsRejectedAtTheWireBoundary() {
        byte[] utf16 = "{\"engineIntelligence\":null}".getBytes(StandardCharsets.UTF_16LE);

        assertThatThrownBy(() -> deserializer.deserialize("transactions.scored", utf16))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_JSON_INVALID");
    }

    @Test
    void duplicateNestedKeysMalformedJsonAndExcessiveDepthUseStableReasons() {
        assertStableInvalid("{\"engineIntelligence\":{\"contractVersion\":2,\"contractVersion\":2}}");
        assertStableInvalid("{\"engineIntelligence\":{\"contractVersion\":2,\"contract\\u0056ersion\":2}}");
        assertStableInvalid("{\"engineIntelligence\":{\"engines\":[{\"engineId\":\"rules.primary\","
                + "\"engineId\":\"rules.primary\"}]}}");
        assertStableInvalid("{\"engineIntelligence\":{");
        assertStableInvalid("{\"engineIntelligence\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}");
    }

    @Test
    void additiveUnknownTopLevelFieldRemainsTolerated() throws Exception {
        String json = fullFixture().replace("\n}", ",\n  \"futureProducerField\": {\"enabled\": true}\n}");

        assertThat(deserializer.deserialize("transactions.scored", utf8(json)).eventContractVersion()).isEqualTo(2);
    }

    @Test
    void kafkaProducerSerializationKeepsTheCurrentNestedContractReadable() throws Exception {
        byte[] fixture;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/fixtures/transaction-scored-event/transaction_scored_event_v2_full_bounded_engine_intelligence.json"
        ))) {
            fixture = input.readAllBytes();
        }
        TransactionScoredEvent event = deserializer.deserialize("transactions.scored", fixture);
        byte[] published = new JacksonKafkaSerializer<TransactionScoredEvent>()
                .serialize("transactions.scored", event);

        assertThat(deserializer.deserialize("transactions.scored", published).engineIntelligence())
                .isEqualTo(event.engineIntelligence());
    }

    private void deserializeWithNestedValueBytes(int nestedBytes, boolean escapedFieldName) {
        String nested = nestedValueWithUtf8ByteSize(nestedBytes);
        String fieldName = escapedFieldName ? "engine\\u0049ntelligence" : "engineIntelligence";
        String json = "{\"" + fieldName + "\":" + nested + "}";
        deserializer.deserialize("transactions.scored", utf8(json));
    }

    private String nestedValueWithUtf8ByteSize(int byteSize) {
        String prefix = "{\"padding\":\"";
        String suffix = "\"}";
        int contentBytes = byteSize
                - prefix.getBytes(StandardCharsets.UTF_8).length
                - suffix.getBytes(StandardCharsets.UTF_8).length;
        String content = "\u00e9".repeat(contentBytes / 2) + (contentBytes % 2 == 0 ? "" : "a");
        return prefix + content + suffix;
    }

    private void assertStableInvalid(String json) {
        assertThatThrownBy(() -> deserializer.deserialize("transactions.scored", utf8(json)))
                .hasRootCauseMessage("ENGINE_INTELLIGENCE_JSON_INVALID");
    }

    private String fullFixture() throws Exception {
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/fixtures/transaction-scored-event/transaction_scored_event_v2_full_bounded_engine_intelligence.json"
        ))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
