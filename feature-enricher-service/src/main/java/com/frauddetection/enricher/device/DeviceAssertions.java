package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Private v1 assertion payloads. Parsing a claim never grants admission or history access. */
final class DeviceAssertions {
    static final int VERSION = 1;
    static final int MAX_PAYLOAD_BYTES = 8_192;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(2).maxStringLength(128).maxNumberLength(19).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> OBSERVATION_FIELDS = Set.of("assertionVersion", "sourceIssuer", "institution",
            "source", "sourceEpoch", "sourceEventId", "transactionKey", "receiptId", "rawCommitment",
            "enrichedCommitment", "customerToken", "deviceToken", "pseudonymKeyVersion", "sourceSequence",
            "eventTime", "observedAt", "bindingAuthority", "providerBasis", "populationId",
            "populationDefinitionVersion", "populationDefinitionCommitment", "policyVersion");
    private static final Set<String> COVERAGE_FIELDS = Set.of("assertionVersion", "sourceIssuer", "institution",
            "source", "sourceEpoch", "coverageId", "populationId", "populationDefinitionVersion",
            "populationDefinitionCommitment", "inclusionRule", "exclusionRule", "pseudonymKeyVersion",
            "sequenceOrigin", "fromSequence", "throughSequence", "coverageStart", "watermark",
            "coverageGeneration", "previousCoverageCommitment", "coverageCommitment", "policyVersion");

    private DeviceAssertions() { }

    static Observation observation(byte[] bytes) {
        JsonNode root = root(bytes, OBSERVATION_FIELDS);
        return new Observation(scope(root), reference(root, "sourceEventId"), token(root, "transactionKey"),
                token(root, "receiptId"), token(root, "rawCommitment"), token(root, "enrichedCommitment"),
                token(root, "customerToken"), token(root, "deviceToken"), keyVersion(root),
                positive(root, "sourceSequence"), time(root, "eventTime"), time(root, "observedAt"),
                reference(root, "bindingAuthority"), reference(root, "providerBasis"),
                reference(root, "populationId"), reference(root, "populationDefinitionVersion"),
                token(root, "populationDefinitionCommitment"), reference(root, "policyVersion"));
    }

    static Coverage coverage(byte[] bytes) {
        JsonNode root = root(bytes, COVERAGE_FIELDS);
        long origin = positive(root, "sequenceOrigin");
        long from = positive(root, "fromSequence");
        long through = positive(root, "throughSequence");
        long generation = positive(root, "coverageGeneration");
        Instant start = time(root, "coverageStart");
        Instant watermark = time(root, "watermark");
        String previous = token(root, "previousCoverageCommitment");
        if (from < origin || through < from || !start.isBefore(watermark)
                || generation == 1 && (from != origin || !previous.equals("0".repeat(64)))
                || generation > 1 && previous.equals("0".repeat(64))) {
            throw invalid();
        }
        return new Coverage(scope(root), reference(root, "coverageId"), reference(root, "populationId"),
                reference(root, "populationDefinitionVersion"), token(root, "populationDefinitionCommitment"),
                reference(root, "inclusionRule"), reference(root, "exclusionRule"), keyVersion(root),
                origin, from, through, start, watermark, generation, previous,
                token(root, "coverageCommitment"), reference(root, "policyVersion"));
    }

    private static JsonNode root(byte[] bytes, Set<String> fields) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) { throw invalid(); }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) { throw invalid(); }
            Set<String> actual = new HashSet<>();
            root.propertyNames().forEach(actual::add);
            if (!actual.equals(fields) || number(root, "assertionVersion") != VERSION) { throw invalid(); }
            return root;
        } catch (Exception malformed) { throw invalid(); }
    }

    private static Scope scope(JsonNode root) {
        return new Scope(reference(root, "sourceIssuer"), reference(root, "institution"),
                reference(root, "source"), reference(root, "sourceEpoch"));
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) { throw invalid(); }
        return value.textValue();
    }

    private static String reference(JsonNode root, String field) { return reference(text(root, field)); }
    private static String token(JsonNode root, String field) { return token(text(root, field)); }
    static String reference(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) { throw invalid(); }
        return value;
    }
    static String token(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) { throw invalid(); }
        return value;
    }
    private static long number(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) { throw invalid(); }
        return value.longValue();
    }
    private static long positive(JsonNode root, String field) {
        long value = number(root, field);
        if (value < 1) { throw invalid(); }
        return value;
    }
    private static int keyVersion(JsonNode root) {
        long version = positive(root, "pseudonymKeyVersion");
        if (version > 65_535) { throw invalid(); }
        return (int) version;
    }
    private static Instant time(JsonNode root, String field) {
        String value = text(root, field);
        Instant time = Instant.parse(value);
        if (!time.toString().equals(value) || time.getNano() % 1_000_000 != 0) { throw invalid(); }
        return time;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("DEVICE_ASSERTION_MALFORMED");
    }

    @JsonIgnoreType
    record Scope(String sourceIssuer, String institution, String source, String sourceEpoch) {
        Scope {
            reference(sourceIssuer); reference(institution); reference(source); reference(sourceEpoch);
        }
        @Override public String toString() { return "DeviceSourceScope[redacted]"; }
    }

    @JsonIgnoreType
    record Observation(Scope scope, String sourceEventId, String transactionKey, String receiptId,
                       String rawCommitment, String enrichedCommitment, String customerToken, String deviceToken,
                       int pseudonymKeyVersion, long sourceSequence, Instant eventTime, Instant observedAt,
                       String bindingAuthority, String providerBasis, String populationId,
                       String populationDefinitionVersion, String populationDefinitionCommitment, String policyVersion) {
        @Override public String toString() { return "DeviceObservationAssertion[redacted]"; }
    }

    @JsonIgnoreType
    record Coverage(Scope scope, String coverageId, String populationId, String populationDefinitionVersion,
                    String populationDefinitionCommitment, String inclusionRule, String exclusionRule,
                    int pseudonymKeyVersion, long sequenceOrigin, long fromSequence, long throughSequence,
                    Instant coverageStart, Instant watermark, long coverageGeneration,
                    String previousCoverageCommitment, String coverageCommitment, String policyVersion) {
        @Override public String toString() { return "DeviceCoverageAssertion[redacted]"; }
    }
}
