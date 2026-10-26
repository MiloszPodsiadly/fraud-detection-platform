package com.frauddetection.common.events.kafka;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;

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
            if (TransactionScoredEvent.class.equals(targetType)) {
                requireBoundedEngineIntelligenceField(data);
            }
            return OBJECT_MAPPER.readValue(data, targetType);
        } catch (Exception exception) {
            throw new SerializationException(
                    "Unable to deserialize Kafka payload from topic " + topic + " as " + targetType.getSimpleName(),
                    exception
            );
        }
    }

    private void requireBoundedEngineIntelligenceField(byte[] data) throws Exception {
        int cursor = skipWhitespace(data, 0);
        if (cursor >= data.length || data[cursor] != '{') {
            return; // Let Jackson report malformed or unexpected root shapes consistently.
        }
        cursor++;
        boolean found = false;
        while (true) {
            cursor = skipWhitespace(data, cursor);
            if (cursor >= data.length || data[cursor] == '}') {
                return;
            }
            if (data[cursor] != '"') {
                return; // Defer syntax errors to Jackson.
            }
            int keyStart = cursor;
            int keyEnd = scanString(data, cursor);
            String key = OBJECT_MAPPER.readValue(Arrays.copyOfRange(data, keyStart, keyEnd), String.class);
            cursor = skipWhitespace(data, keyEnd);
            if (cursor >= data.length || data[cursor] != ':') {
                return;
            }
            int valueStart = skipWhitespace(data, cursor + 1);
            int valueEnd = scanValue(data, valueStart);
            if ("engineIntelligence".equals(key)) {
                if (found) {
                    throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DUPLICATE_JSON_FIELD");
                }
                found = true;
                com.frauddetection.common.events.intelligence.EngineIntelligenceVersionedIdentity
                        .requireSerializedSize(valueEnd - valueStart);
            }
            cursor = skipWhitespace(data, valueEnd);
            if (cursor < data.length && data[cursor] == ',') {
                cursor++;
                continue;
            }
            return;
        }
    }

    private int scanValue(byte[] data, int start) {
        if (start >= data.length) {
            return start;
        }
        if (data[start] == '"') {
            return scanString(data, start);
        }
        if (data[start] != '{' && data[start] != '[') {
            int cursor = start;
            while (cursor < data.length && data[cursor] != ',' && data[cursor] != '}' && data[cursor] != ']'
                    && !isWhitespace(data[cursor])) {
                cursor++;
            }
            return cursor;
        }
        byte[] closers = new byte[Math.min(32, data.length - start)];
        int depth = 0;
        int cursor = start;
        while (cursor < data.length) {
            byte current = data[cursor];
            if (current == '"') {
                cursor = scanString(data, cursor);
                continue;
            }
            if (current == '{' || current == '[') {
                if (depth == closers.length) {
                    int capacity = Math.min(data.length - start, Math.max(1, closers.length * 2));
                    closers = Arrays.copyOf(closers, capacity);
                }
                closers[depth++] = current == '{' ? (byte) '}' : (byte) ']';
            } else if (current == '}' || current == ']') {
                if (depth == 0 || closers[--depth] != current) {
                    return cursor + 1;
                }
                if (depth == 0) {
                    return cursor + 1;
                }
            }
            cursor++;
        }
        return data.length;
    }

    private int scanString(byte[] data, int start) {
        int cursor = start + 1;
        while (cursor < data.length) {
            byte current = data[cursor++];
            if (current == '\\') {
                cursor++;
            } else if (current == '"') {
                return cursor;
            }
        }
        return data.length;
    }

    private int skipWhitespace(byte[] data, int start) {
        int cursor = start;
        while (cursor < data.length && isWhitespace(data[cursor])) {
            cursor++;
        }
        return cursor;
    }

    private boolean isWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }
}
