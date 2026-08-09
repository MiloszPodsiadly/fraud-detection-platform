package com.frauddetection.common.events.features;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FeatureSnapshotWireValueDeserializer extends ValueDeserializer<Map<String, Object>> {
    @Override
    public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        JsonToken token = parser.currentToken();
        if (token == null) {
            token = parser.nextToken();
        }
        if (token != JsonToken.START_OBJECT) {
            throw context.wrongTokenException(parser, Map.class, JsonToken.START_OBJECT, "featureSnapshot must be a JSON object");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = parser.currentName();
            parser.nextToken();
            normalized.put(key, FeatureSnapshotWireValueNormalizer.normalizeJsonParserValue(key, parser, context));
        }
        return Map.copyOf(normalized);
    }
}
