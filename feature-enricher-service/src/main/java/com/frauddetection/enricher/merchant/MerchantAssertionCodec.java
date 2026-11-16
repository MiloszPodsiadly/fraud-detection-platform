package com.frauddetection.enricher.merchant;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict canonical JSON codec. The verifier authenticates the exact input bytes, never a reserialized claim. */
final class MerchantAssertionCodec {
    static final int MAX_ASSERTION_BYTES = 8_192;
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern COMMITMENT = Pattern.compile("[0-9a-f]{64}");
    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2100-01-01T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(2).maxStringLength(128).maxNumberLength(19).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> OBSERVATION_FIELDS = Set.of(
            "assertionVersion", "sourceIssuer", "institution", "source", "sourceEpoch", "merchantGrantId",
            "merchantGrantVersion", "merchantPolicyVersion", "identityVersion",
            "sourceEventId", "transactionCommitment", "occurrenceCommitment", "receiptId", "rawCommitment",
            "enrichedCommitment", "merchantPseudonym", "mappingVersion", "mappingCommitment", "feedId",
            "populationId", "populationDefinitionVersion", "populationDefinitionCommitment", "sequence",
            "eventTime", "sourceObservedAt");
    private static final Set<String> COVERAGE_FIELDS = Set.of(
            "assertionVersion", "sourceIssuer", "institution", "source", "sourceEpoch", "merchantGrantId",
            "merchantGrantVersion", "merchantPolicyVersion", "identityVersion",
            "coverageId", "feedId", "populationId", "populationDefinitionVersion", "populationDefinitionCommitment",
            "inclusionRulesCommitment", "exclusionRulesCommitment", "sequenceOrigin", "fromSequence", "throughSequence",
            "coverageStart", "watermark", "generation", "previousCoverageCommitment");

    private MerchantAssertionCodec() { }

    static MerchantObservationAssertion parseObservation(byte[] input) {
        byte[] bytes = bounded(input);
        JsonNode root = parse(bytes);
        exactFields(root, OBSERVATION_FIELDS);
        MerchantObservationAssertion claim = new MerchantObservationAssertion(
                integer(root, "assertionVersion"), string(root, "sourceIssuer"), string(root, "institution"),
                string(root, "source"), string(root, "sourceEpoch"), string(root, "merchantGrantId"),
                integer(root, "merchantGrantVersion"), string(root, "merchantPolicyVersion"), integer(root, "identityVersion"),
                string(root, "sourceEventId"), string(root, "transactionCommitment"), string(root, "occurrenceCommitment"),
                string(root, "receiptId"), string(root, "rawCommitment"), string(root, "enrichedCommitment"),
                string(root, "merchantPseudonym"), integer(root, "mappingVersion"), string(root, "mappingCommitment"),
                string(root, "feedId"), string(root, "populationId"), integer(root, "populationDefinitionVersion"),
                string(root, "populationDefinitionCommitment"), longInteger(root, "sequence"),
                timestamp(root, "eventTime"), timestamp(root, "sourceObservedAt"));
        requireCanonical(bytes, encodeObservation(claim));
        return claim;
    }

    static MerchantCoverageAssertion parseCoverage(byte[] input) {
        byte[] bytes = bounded(input);
        JsonNode root = parse(bytes);
        exactFields(root, COVERAGE_FIELDS);
        MerchantCoverageAssertion claim = new MerchantCoverageAssertion(
                integer(root, "assertionVersion"), string(root, "sourceIssuer"), string(root, "institution"),
                string(root, "source"), string(root, "sourceEpoch"), string(root, "merchantGrantId"),
                integer(root, "merchantGrantVersion"), string(root, "merchantPolicyVersion"), integer(root, "identityVersion"),
                string(root, "coverageId"), string(root, "feedId"), string(root, "populationId"),
                integer(root, "populationDefinitionVersion"), string(root, "populationDefinitionCommitment"),
                string(root, "inclusionRulesCommitment"), string(root, "exclusionRulesCommitment"),
                longInteger(root, "sequenceOrigin"), longInteger(root, "fromSequence"), longInteger(root, "throughSequence"),
                timestamp(root, "coverageStart"), timestamp(root, "watermark"), longInteger(root, "generation"),
                nullableString(root, "previousCoverageCommitment"));
        requireCanonical(bytes, encodeCoverage(claim));
        return claim;
    }

    static byte[] encodeObservation(MerchantObservationAssertion claim) {
        return json("assertionVersion", claim.assertionVersion(), "sourceIssuer", claim.sourceIssuer(),
                "institution", claim.institution(), "source", claim.source(), "sourceEpoch", claim.sourceEpoch(),
                "merchantGrantId", claim.merchantGrantId(), "merchantGrantVersion", claim.merchantGrantVersion(),
                "merchantPolicyVersion", claim.merchantPolicyVersion(),
                "identityVersion", claim.identityVersion(), "sourceEventId", claim.sourceEventId(),
                "transactionCommitment", claim.transactionCommitment(), "occurrenceCommitment", claim.occurrenceCommitment(),
                "receiptId", claim.receiptId(), "rawCommitment", claim.rawCommitment(), "enrichedCommitment", claim.enrichedCommitment(),
                "merchantPseudonym", claim.merchantPseudonym(), "mappingVersion", claim.mappingVersion(),
                "mappingCommitment", claim.mappingCommitment(), "feedId", claim.feedId(), "populationId", claim.populationId(),
                "populationDefinitionVersion", claim.populationDefinitionVersion(),
                "populationDefinitionCommitment", claim.populationDefinitionCommitment(), "sequence", claim.sequence(),
                "eventTime", claim.eventTime().toString(), "sourceObservedAt", claim.sourceObservedAt().toString());
    }

    static byte[] encodeCoverage(MerchantCoverageAssertion claim) {
        return json("assertionVersion", claim.assertionVersion(), "sourceIssuer", claim.sourceIssuer(),
                "institution", claim.institution(), "source", claim.source(), "sourceEpoch", claim.sourceEpoch(),
                "merchantGrantId", claim.merchantGrantId(), "merchantGrantVersion", claim.merchantGrantVersion(),
                "merchantPolicyVersion", claim.merchantPolicyVersion(),
                "identityVersion", claim.identityVersion(), "coverageId", claim.coverageId(), "feedId", claim.feedId(),
                "populationId", claim.populationId(), "populationDefinitionVersion", claim.populationDefinitionVersion(),
                "populationDefinitionCommitment", claim.populationDefinitionCommitment(),
                "inclusionRulesCommitment", claim.inclusionRulesCommitment(), "exclusionRulesCommitment", claim.exclusionRulesCommitment(),
                "sequenceOrigin", claim.sequenceOrigin(), "fromSequence", claim.fromSequence(),
                "throughSequence", claim.throughSequence(), "coverageStart", claim.coverageStart().toString(),
                "watermark", claim.watermark().toString(), "generation", claim.generation(),
                "previousCoverageCommitment", claim.previousCoverageCommitment());
    }

    static void reference(String value) {
        if (value == null || !REFERENCE.matcher(value).matches()) { throw malformed(); }
    }

    static void commitment(String value) {
        if (value == null || !COMMITMENT.matcher(value).matches()) { throw malformed(); }
    }

    static void time(Instant value) {
        if (value == null || value.isBefore(EARLIEST) || !value.isBefore(LATEST) || value.getNano() % 1_000_000 != 0) {
            throw malformed();
        }
    }

    private static byte[] json(Object... fields) {
        if (fields.length % 2 != 0) { throw malformed(); }
        StringBuilder output = new StringBuilder(fields.length * 20).append('{');
        for (int index = 0; index < fields.length; index += 2) {
            if (index != 0) { output.append(','); }
            output.append('"').append(fields[index]).append("\":");
            Object value = fields[index + 1];
            if (value == null) { output.append("null"); }
            else if (value instanceof Number) { output.append(value); }
            else { output.append('"').append(value).append('"'); }
        }
        byte[] bytes = output.append('}').toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ASSERTION_BYTES) { throw malformed(); }
        return bytes;
    }

    private static byte[] bounded(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_ASSERTION_BYTES) { throw malformed(); }
        return input.clone();
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) { throw malformed(); }
            return root;
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException argument
                    && "MERCHANT_ASSERTION_MALFORMED".equals(argument.getMessage())) { throw argument; }
            throw malformed();
        } catch (Exception invalid) {
            throw malformed();
        }
    }

    private static void exactFields(JsonNode root, Set<String> expected) {
        if (root.size() != expected.size()) { throw malformed(); }
        Set<String> actual = new HashSet<>();
        root.propertyNames().forEach(actual::add);
        if (!actual.equals(expected)) { throw malformed(); }
    }

    private static String string(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) { throw malformed(); }
        return value.textValue();
    }

    private static String nullableString(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) { return null; }
        if (!value.isTextual()) { throw malformed(); }
        return value.textValue();
    }

    private static int integer(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) { throw malformed(); }
        return value.intValue();
    }

    private static long longInteger(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) { throw malformed(); }
        return value.longValue();
    }

    private static Instant timestamp(JsonNode root, String field) {
        String encoded = string(root, field);
        try {
            Instant value = Instant.parse(encoded);
            time(value);
            if (!value.toString().equals(encoded)) { throw malformed(); }
            return value;
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static void requireCanonical(byte[] original, byte[] canonical) {
        if (!Arrays.equals(original, canonical)) { throw malformed(); }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("MERCHANT_ASSERTION_MALFORMED");
    }
}
