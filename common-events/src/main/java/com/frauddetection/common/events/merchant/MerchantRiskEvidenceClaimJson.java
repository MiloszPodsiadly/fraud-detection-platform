package com.frauddetection.common.events.merchant;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Internal JSON boundary only. It does not register a Kafka serializer, endpoint, or admitted evidence reader. */
public final class MerchantRiskEvidenceClaimJson {
    public static final int MAX_SERIALIZED_BYTES = 4_096;
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(128).maxNumberLength(20).build())
            .build();
    private static final JsonMapper MAPPER = JsonMapper.builder(JSON_FACTORY)
            .findAndAddModules()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private MerchantRiskEvidenceClaimJson() {
    }

    public static byte[] encode(MerchantRiskEvidenceClaim claim) {
        if (claim == null) {
            throw invalid();
        }
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(claim);
            requireSize(bytes);
            return bytes;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static MerchantRiskEvidenceClaim decode(byte[] bytes) {
        requireSize(bytes);
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (json.stripLeading().isEmpty() || json.stripLeading().charAt(0) != '{') {
                throw invalid();
            }
            requireStringTimestamps(json);
            MerchantRiskEvidenceClaim claim = MAPPER.readValue(json, MerchantRiskEvidenceClaim.class);
            if (claim == null) {
                throw invalid();
            }
            return claim;
        } catch (CharacterCodingException | RuntimeException exception) {
            // Parser messages and causes can contain input identifiers or arbitrary field names. Do not propagate them.
            throw invalid();
        }
    }

    private static void requireStringTimestamps(String json) {
        // Instant readers can accept numeric epochs even when their declared JSON shape is STRING.
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                    continue;
                }
                String name = parser.currentName();
                boolean required = switch (name) {
                    case "eventTime", "observedAt", "windowStart", "asOf" -> true;
                    default -> false;
                };
                boolean nullable = "coveredFrom".equals(name) || "coveredThrough".equals(name);
                if (required || nullable) {
                    JsonToken value = parser.nextToken();
                    if (value != JsonToken.VALUE_STRING && !(nullable && value == JsonToken.VALUE_NULL)) {
                        throw invalid();
                    }
                }
            }
        }
    }

    private static void requireSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SERIALIZED_BYTES) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("MERCHANT_CLAIM_JSON_INVALID");
    }
}
