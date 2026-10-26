package com.frauddetection.common.events.kafka;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class JacksonKafkaSerializer<T> implements Serializer<T> {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null;
        }

        try {
            byte[] serialized = OBJECT_MAPPER.writeValueAsBytes(data);
            if (data instanceof TransactionScoredEvent) {
                EngineIntelligenceKafkaPayloadBoundary.requireBoundedEngineIntelligenceField(serialized);
            }
            return serialized;
        } catch (Exception exception) {
            throw new SerializationException("Unable to serialize Kafka payload for topic " + topic, exception);
        }
    }
}
