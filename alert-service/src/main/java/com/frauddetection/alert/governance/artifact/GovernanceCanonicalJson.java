package com.frauddetection.alert.governance.artifact;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class GovernanceCanonicalJson {

    private GovernanceCanonicalJson() {
    }

    public static byte[] serialize(ObjectMapper objectMapper, JsonNode value) {
        try {
            return (objectMapper.writeValueAsString(sorted(objectMapper, value)) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException exception) {
            throw new GovernanceArtifactReadException("governance artifact canonical JSON unavailable", exception);
        }
    }

    private static JsonNode sorted(ObjectMapper objectMapper, JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            value.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), sorted(objectMapper, entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(sorted(objectMapper, item)));
            return result;
        }
        return value;
    }
}
