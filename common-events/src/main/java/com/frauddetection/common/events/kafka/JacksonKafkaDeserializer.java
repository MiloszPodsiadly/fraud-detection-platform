package com.frauddetection.common.events.kafka;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Pattern;

public final class JacksonKafkaDeserializer<T> implements Deserializer<T> {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private static final Pattern BOUNDED_REASON_CODE = Pattern.compile("[A-Z0-9_]{1,96}");

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
            if (TransactionScoredEvent.class.equals(targetType)) {
                EngineIntelligenceKafkaPayloadBoundary.requireBoundedEngineIntelligenceField(data);
            }
            return OBJECT_MAPPER.readValue(data, targetType);
        } catch (Exception exception) {
            throw new SerializationException(
                    "Unable to deserialize Kafka payload from topic " + topic + " as " + targetType.getSimpleName(),
                    TransactionScoredEvent.class.equals(targetType)
                            ? boundedEngineIntelligenceFailure(exception)
                            : exception
            );
        }
    }

    private RuntimeException boundedEngineIntelligenceFailure(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException illegalArgument
                    && illegalArgument.getMessage() != null
                    && BOUNDED_REASON_CODE.matcher(illegalArgument.getMessage()).matches()) {
                return new IllegalArgumentException(illegalArgument.getMessage());
            }
        }
        if (exception instanceof JacksonException) {
            return new IllegalArgumentException("ENGINE_INTELLIGENCE_JSON_INVALID");
        }
        return new IllegalArgumentException("ENGINE_INTELLIGENCE_PAYLOAD_INVALID");
    }
}
