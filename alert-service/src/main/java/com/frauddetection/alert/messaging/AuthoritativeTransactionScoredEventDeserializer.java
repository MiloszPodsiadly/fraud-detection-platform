package com.frauddetection.alert.messaging;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class AuthoritativeTransactionScoredEventDeserializer
        implements Deserializer<TransactionScoredEvent> {

    private static final String OPTIONAL_EVIDENCE_FIELD = "mlPredictionEvidence";
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Override
    public TransactionScoredEvent deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(data);
            if (!(root instanceof ObjectNode eventJson)) {
                throw new IllegalArgumentException("transaction-scored payload must be a JSON object");
            }
            eventJson.remove(OPTIONAL_EVIDENCE_FIELD);
            return OBJECT_MAPPER.treeToValue(eventJson, TransactionScoredEvent.class);
        } catch (Exception exception) {
            throw new SerializationException(
                    "Unable to deserialize authoritative transaction-scored payload from topic " + topic,
                    exception
            );
        }
    }
}
