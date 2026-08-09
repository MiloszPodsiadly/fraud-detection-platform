package com.frauddetection.common.events.features;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FeatureSnapshotWireValueNormalizer {
    private FeatureSnapshotWireValueNormalizer() {
    }

    public static Map<String, Object> normalize(Map<String, Object> source) {
        Objects.requireNonNull(source, "featureSnapshot is required");
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "featureSnapshot must not contain null keys");
            Object value = Objects.requireNonNull(entry.getValue(), "featureSnapshot must not contain null values");
            normalized.put(key, normalizeRuntimeValue(key, value));
        }
        return Map.copyOf(normalized);
    }

    public static Map<String, Object> normalizeJsonObject(JsonNode root) {
        Objects.requireNonNull(root, "featureSnapshot JSON is required");
        if (!root.isObject()) {
            throw new IllegalArgumentException("featureSnapshot must be a JSON object");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            normalized.put(entry.getKey(), normalizeJsonValue(entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(normalized);
    }

    public static Object normalizeJsonValue(String key, JsonNode value) {
        Objects.requireNonNull(key, "featureSnapshot key is required");
        Objects.requireNonNull(value, "featureSnapshot value is required");
        return FraudFeatureContract.expectedScalarTypeFor(key)
                .map(type -> normalizeRegisteredJsonValue(type, value))
                .orElseGet(() -> genericJsonValue(value));
    }

    public static Object normalizeJsonParserValue(
            String key,
            JsonParser parser,
            DeserializationContext context
    ) throws JacksonException {
        Objects.requireNonNull(key, "featureSnapshot key is required");
        Objects.requireNonNull(parser, "JSON parser is required");
        Objects.requireNonNull(context, "deserialization context is required");
        Optional<String> type = FraudFeatureContract.expectedScalarTypeFor(key);
        if (type.isPresent()) {
            return normalizeRegisteredParserValue(type.get(), parser, context);
        }
        return genericParserValue(parser, context);
    }

    private static Object normalizeRuntimeValue(String key, Object value) {
        return FraudFeatureContract.expectedScalarTypeFor(key)
                .map(type -> normalizeRegisteredRuntimeValue(type, value))
                .orElse(value);
    }

    private static Object normalizeRegisteredJsonValue(String type, JsonNode value) {
        return switch (type) {
            case FraudFeatureContract.TYPE_BOOLEAN -> value.isBoolean() ? value.booleanValue() : genericJsonValue(value);
            case FraudFeatureContract.TYPE_INTEGER -> normalizeJsonInteger(value);
            case FraudFeatureContract.TYPE_LONG -> value.isIntegralNumber() ? value.longValue() : genericJsonValue(value);
            case FraudFeatureContract.TYPE_DOUBLE -> normalizeJsonDouble(value);
            case FraudFeatureContract.TYPE_DECIMAL -> value.isNumber() ? value.decimalValue() : genericJsonValue(value);
            case FraudFeatureContract.TYPE_STRING -> value.isTextual() ? value.textValue() : genericJsonValue(value);
            default -> genericJsonValue(value);
        };
    }

    private static Object normalizeRegisteredRuntimeValue(String type, Object value) {
        return switch (type) {
            case FraudFeatureContract.TYPE_BOOLEAN -> value instanceof Boolean ? value : value;
            case FraudFeatureContract.TYPE_INTEGER -> normalizeRuntimeInteger(value);
            case FraudFeatureContract.TYPE_LONG -> value instanceof Long ? value : value;
            case FraudFeatureContract.TYPE_DOUBLE -> normalizeRuntimeDouble(value);
            case FraudFeatureContract.TYPE_DECIMAL -> normalizeRuntimeDecimal(value);
            case FraudFeatureContract.TYPE_STRING -> value instanceof String ? value : value;
            default -> value;
        };
    }

    private static Object normalizeRegisteredParserValue(
            String type,
            JsonParser parser,
            DeserializationContext context
    ) throws JacksonException {
        return switch (type) {
            case FraudFeatureContract.TYPE_BOOLEAN -> parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE
                    ? parser.getBooleanValue()
                    : genericParserValue(parser, context);
            case FraudFeatureContract.TYPE_INTEGER -> parser.currentToken() == JsonToken.VALUE_NUMBER_INT
                    ? normalizeParserInteger(parser)
                    : genericParserValue(parser, context);
            case FraudFeatureContract.TYPE_LONG -> parser.currentToken() == JsonToken.VALUE_NUMBER_INT
                    ? parser.getLongValue()
                    : genericParserValue(parser, context);
            case FraudFeatureContract.TYPE_DOUBLE -> isNumberToken(parser.currentToken())
                    ? parser.getDoubleValue()
                    : genericParserValue(parser, context);
            case FraudFeatureContract.TYPE_DECIMAL -> isNumberToken(parser.currentToken())
                    ? parser.getDecimalValue()
                    : genericParserValue(parser, context);
            case FraudFeatureContract.TYPE_STRING -> parser.currentToken() == JsonToken.VALUE_STRING
                    ? parser.getString()
                    : genericParserValue(parser, context);
            default -> genericParserValue(parser, context);
        };
    }

    private static Object normalizeParserInteger(JsonParser parser) throws JacksonException {
        long longValue = parser.getLongValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            return longValue;
        }
        return (int) longValue;
    }

    private static Object normalizeJsonInteger(JsonNode value) {
        if (!value.isIntegralNumber()) {
            return genericJsonValue(value);
        }
        long longValue = value.longValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            return longValue;
        }
        return (int) longValue;
    }

    private static Object normalizeRuntimeInteger(Object value) {
        if (value instanceof Integer) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Long || value instanceof BigInteger) {
            BigInteger integer = toBigInteger(value);
            if (integer.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0
                    && integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
                return integer.intValueExact();
            }
        }
        return value;
    }

    private static Object normalizeJsonDouble(JsonNode value) {
        if (!value.isNumber()) {
            return genericJsonValue(value);
        }
        return value.doubleValue();
    }

    private static Object normalizeRuntimeDouble(Object value) {
        if (value instanceof Double) {
            return value;
        }
        if (value instanceof Float floatValue) {
            return floatValue.doubleValue();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return new BigDecimal(value.toString()).doubleValue();
        }
        return value;
    }

    private static Object normalizeRuntimeDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger) {
            return new BigDecimal(value.toString());
        }
        return value;
    }

    private static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger bigInteger) {
            return bigInteger;
        }
        return BigInteger.valueOf(((Number) value).longValue());
    }

    private static Object genericJsonValue(JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            long longValue = value.longValue();
            if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                return (int) longValue;
            }
            return longValue;
        }
        if (value.isFloatingPointNumber()) {
            return value.decimalValue();
        }
        if (value.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode child : value.values()) {
                values.add(genericJsonValue(child));
            }
            return List.copyOf(values);
        }
        if (value.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                values.put(entry.getKey(), genericJsonValue(entry.getValue()));
            }
            return Map.copyOf(values);
        }
        return value.toString();
    }

    private static Object genericParserValue(JsonParser parser, DeserializationContext context) throws JacksonException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return parser.getString();
        }
        if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
            return parser.getBooleanValue();
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return normalizeParserInteger(parser);
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDecimalValue();
        }
        if (token == JsonToken.START_ARRAY) {
            return genericParserArray(parser, context);
        }
        if (token == JsonToken.START_OBJECT) {
            return genericParserObject(parser, context);
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        return parser.getText();
    }

    private static List<Object> genericParserArray(JsonParser parser, DeserializationContext context) throws JacksonException {
        List<Object> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            values.add(genericParserValue(parser, context));
        }
        return List.copyOf(values);
    }

    private static Map<String, Object> genericParserObject(JsonParser parser, DeserializationContext context) throws JacksonException {
        Map<String, Object> values = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = parser.currentName();
            parser.nextToken();
            values.put(key, genericParserValue(parser, context));
        }
        return Map.copyOf(values);
    }

    private static boolean isNumberToken(JsonToken token) {
        return token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT;
    }
}
