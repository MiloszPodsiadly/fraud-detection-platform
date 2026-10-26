package com.frauddetection.common.events.kafka;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import java.util.Arrays;

/** Streaming validation of the raw Engine Intelligence JSON value on Kafka wire boundaries. */
final class EngineIntelligenceKafkaPayloadBoundary {

    private static final String FIELD_NAME = "engineIntelligence";
    private static final int MAX_JSON_NESTING_DEPTH = 64;
    private static final JsonFactory ROOT_JSON_FACTORY = JsonFactory.builder().build();
    private static final JsonFactory ENGINE_INTELLIGENCE_JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                    .build())
            .build();

    private EngineIntelligenceKafkaPayloadBoundary() {
    }

    static void requireBoundedEngineIntelligenceField(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        requireUtf8JsonObject(payload);

        try (JsonParser parser = ROOT_JSON_FACTORY.createParser(payload)) {
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_OBJECT) {
                throw invalidJson();
            }

            boolean foundEngineIntelligence = false;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token == null || token != JsonToken.PROPERTY_NAME) {
                    throw invalidJson();
                }
                String fieldName = parser.currentName();
                boolean engineIntelligenceField = FIELD_NAME.equals(fieldName);
                if (engineIntelligenceField && foundEngineIntelligence) {
                    throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DUPLICATE_JSON_FIELD");
                }
                token = parser.nextToken();
                if (token == null) {
                    throw invalidJson();
                }

                long valueStart = parser.currentTokenLocation().getByteOffset();
                parser.skipChildren();
                long valueEnd = parser.currentLocation().getByteOffset();

                if (engineIntelligenceField) {
                    foundEngineIntelligence = true;
                    if (valueStart < 0 || valueEnd < valueStart || valueEnd > payload.length) {
                        throw invalidJson();
                    }
                    FraudEngineIdentityContract.requireEngineIntelligenceSerializedSize(
                            Math.toIntExact(valueEnd - valueStart)
                    );
                    requireUniqueNestedProperties(Arrays.copyOfRange(payload, (int) valueStart, (int) valueEnd));
                }
            }
            requireSingleJsonRoot(parser);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Keep malformed JSON, duplicate names (including escaped aliases), and parser-limit failures
            // out of quarantine headers and logs as stable, bounded reason codes.
            throw invalidJson();
        }
    }

    private static void requireUtf8JsonObject(byte[] payload) {
        int cursor = 0;
        while (cursor < payload.length && isJsonWhitespace(payload[cursor])) {
            cursor++;
        }
        if (cursor >= payload.length
                || payload[cursor] != '{'
                || (cursor + 1 < payload.length && payload[cursor + 1] == 0)) {
            throw invalidJson();
        }
    }

    private static boolean isJsonWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static void requireUniqueNestedProperties(byte[] engineIntelligenceValue) {
        try (JsonParser parser = ENGINE_INTELLIGENCE_JSON_FACTORY.createParser(engineIntelligenceValue)) {
            if (parser.nextToken() == null) {
                throw invalidJson();
            }
            parser.skipChildren();
            if (parser.nextToken() != null) {
                throw invalidJson();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidJson();
        }
    }

    private static void requireSingleJsonRoot(JsonParser parser) {
        if (parser.nextToken() != null) {
            throw invalidJson();
        }
    }

    private static IllegalArgumentException invalidJson() {
        return new IllegalArgumentException("ENGINE_INTELLIGENCE_JSON_INVALID");
    }
}
