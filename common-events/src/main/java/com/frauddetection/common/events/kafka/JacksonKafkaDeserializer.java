package com.frauddetection.common.events.kafka;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class JacksonKafkaDeserializer<T> implements Deserializer<T> {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private final Class<T> targetType;

    public JacksonKafkaDeserializer(Class<T> targetType) {
        this.targetType = targetType;
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            return OBJECT_MAPPER.readValue(data, targetType);
        } catch (Exception exception) {
            throw new SerializationException(
                    "Unable to deserialize Kafka payload from topic " + topic + " as " + targetType.getSimpleName(),
                    exception
            );
        }
    }
}