package com.frauddetection.common.events.merchant;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Internal, unadmitted claims only. No constructor, enum or binding comparison authenticates a source.
 * AVAILABLE facts and complete coverage are deliberately unrepresentable. Not a Kafka/public DTO.
 * Use {@link MerchantRiskEvidenceClaimJson} for the bounded, duplicate-rejecting internal JSON boundary.
 */
@JsonPropertyOrder({"schemaVersion", "scope", "occurrence", "source", "history", "signals"})
public record MerchantRiskEvidenceClaim(
        int schemaVersion,
        ScopeClaim scope,
        OccurrenceClaim occurrence,
        SourceClaim source,
        HistoryWindowClaim history,
        List<SignalFact> signals
) {
    public static final int VERSION = 1;
    public static final int MAX_CANONICAL_BYTES = 4_096;
    /** Software resource ceiling, not an approved retention or production analysis window. */
    public static final Duration MAX_WINDOW = Duration.ofDays(30);
    private static final Instant MIN_TIME = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("2100-01-01T00:00:00Z");
    private static final Pattern OPAQUE_REFERENCE = Pattern.compile("[0-9a-f]{64}");

    public MerchantRiskEvidenceClaim {
        require(schemaVersion == VERSION, "VERSION");
        require(scope != null && occurrence != null && source != null && history != null, "REQUIRED_FIELD");
        require(occurrence.eventTime().equals(history.asOf()), "OCCURRENCE_CUTOFF");
        require(!source.observedAt().isAfter(history.asOf()), "SOURCE_TIME");
        require(signals != null && signals.size() == Signal.values().length, "SIGNALS");
        EnumSet<Signal> seen = EnumSet.noneOf(Signal.class);
        for (SignalFact fact : signals) {
            require(fact != null && seen.add(fact.signal()), "SIGNALS");
            Reason assertedReason = switch (source.assertion()) {
                case CLIENT_ASSERTED -> Reason.CLIENT_ASSERTION;
                case SYNTHETIC -> Reason.SYNTHETIC_INPUT;
                case UNSPECIFIED -> null;
            };
            if (assertedReason != null) {
                require(fact.status() == Status.UNTRUSTED && fact.reason() == assertedReason, "SOURCE_FACT_CONFLICT");
            } else {
                require(fact.status() != Status.UNTRUSTED, "SOURCE_FACT_CONFLICT");
            }
        }
        signals = signals.stream().sorted(Comparator.comparing(SignalFact::signal)).toList();
    }

    /** Equality of supplied claims only. It neither verifies the expected values nor grants admission. */
    public void requireClaimedBinding(ScopeClaim expectedScope, OccurrenceClaim expectedOccurrence) {
        require(scope.equals(expectedScope) && occurrence.equals(expectedOccurrence), "BINDING_MISMATCH");
    }

    /** Versioned, UTF-8 byte-length-delimited identity of this claim, not a signature or admission proof. */
    public byte[] canonicalBytes() {
        StringBuilder out = new StringBuilder(1_536);
        MerchantCanonicalEncoding.append(out, "merchant-risk/evidence-claim", schemaVersion,
                scope.issuerRef(), scope.institutionRef(), scope.sourceRef(), scope.sourceEpochRef(),
                scope.merchantRef(), scope.identityVersion(), occurrence.sourceEventRef(),
                occurrence.transactionRef(), occurrence.scoringOccurrenceRef(), occurrence.sourceSequence(),
                occurrence.eventTime(), occurrence.payloadCommitmentClaim(), source.assertion(), source.observedAt(),
                history.windowStart(), history.asOf(), history.coveredFrom(), history.coveredThrough(), history.coverage());
        for (SignalFact fact : signals) {
            MerchantCanonicalEncoding.append(out, fact.signal(), fact.status(), fact.reason());
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_CANONICAL_BYTES, "CANONICAL_SIZE");
        return bytes;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw invalid("UNKNOWN_FIELD");
    }

    @Override
    public String toString() {
        return "MerchantRiskEvidenceClaim[NOT_ADMITTED, redacted]";
    }

    public enum SourceAssertion { UNSPECIFIED, CLIENT_ASSERTED, SYNTHETIC }
    public enum CoverageClaim { OBSERVED_SAMPLE, INCOMPLETE }
    public enum Status { UNAVAILABLE, UNTRUSTED, UNSUPPORTED, INVALID }
    public enum Reason { NOT_ADMITTED, NO_SOURCE, CLIENT_ASSERTION, SYNTHETIC_INPUT,
        METHODOLOGY_MISSING, INVALID_PROVENANCE, INVALID_TIME }
    public enum Signal { MERCHANT_FRAUD_RATE, MERCHANT_ACTIVITY_SPIKE, MERCHANT_CATEGORY_CONTEXT,
        MERCHANT_POPULATION_CONCENTRATION, MERCHANT_FIRST_OBSERVED_IN_WINDOW }

    /** Every reference and mapping version is a claim; no pseudonym key custody is asserted. */
    @JsonPropertyOrder({"issuerRef", "institutionRef", "sourceRef", "sourceEpochRef", "merchantRef", "identityVersion"})
    public record ScopeClaim(String issuerRef, String institutionRef, String sourceRef, String sourceEpochRef,
                             String merchantRef, int identityVersion) {
        public ScopeClaim {
            reference(issuerRef);
            reference(institutionRef);
            reference(sourceRef);
            reference(sourceEpochRef);
            reference(merchantRef);
            require(identityVersion >= 1 && identityVersion <= 65_535, "IDENTITY_VERSION");
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw invalid("UNKNOWN_FIELD"); }

        @Override
        public String toString() { return "ScopeClaim[redacted]"; }
    }

    @JsonPropertyOrder({"sourceEventRef", "transactionRef", "scoringOccurrenceRef", "sourceSequence",
            "eventTime", "payloadCommitmentClaim"})
    public record OccurrenceClaim(String sourceEventRef, String transactionRef, String scoringOccurrenceRef,
                                  long sourceSequence, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant eventTime,
                                  String payloadCommitmentClaim) {
        public OccurrenceClaim {
            reference(sourceEventRef);
            reference(transactionRef);
            reference(scoringOccurrenceRef);
            reference(payloadCommitmentClaim);
            require(sourceSequence >= 1, "SOURCE_SEQUENCE");
            time(eventTime);
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw invalid("UNKNOWN_FIELD"); }

        @Override
        public String toString() { return "OccurrenceClaim[redacted]"; }
    }

    @JsonPropertyOrder({"assertion", "observedAt"})
    public record SourceClaim(SourceAssertion assertion, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant observedAt) {
        public SourceClaim {
            require(assertion != null, "SOURCE_ASSERTION");
            time(observedAt);
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw invalid("UNKNOWN_FIELD"); }

        @Override
        public String toString() { return "SourceClaim[redacted]"; }
    }

    /** coveredThrough is an exclusive claimed interval end, never an authenticated coverage watermark. */
    @JsonPropertyOrder({"windowStart", "asOf", "coveredFrom", "coveredThrough", "coverage"})
    public record HistoryWindowClaim(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant windowStart,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) Instant asOf,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) Instant coveredFrom,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) Instant coveredThrough,
                                     CoverageClaim coverage) {
        public HistoryWindowClaim {
            time(windowStart);
            time(asOf);
            require(windowStart.isBefore(asOf) && Duration.between(windowStart, asOf).compareTo(MAX_WINDOW) <= 0,
                    "WINDOW");
            require(coverage != null, "COVERAGE");
            if (coverage == CoverageClaim.OBSERVED_SAMPLE) {
                time(coveredFrom);
                time(coveredThrough);
                require(!coveredFrom.isBefore(windowStart) && coveredFrom.isBefore(coveredThrough)
                        && !coveredThrough.isAfter(asOf), "COVERAGE_WINDOW");
            } else {
                require(coveredFrom == null && coveredThrough == null, "COVERAGE_WINDOW");
            }
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw invalid("UNKNOWN_FIELD"); }

        @Override
        public String toString() { return "HistoryWindowClaim[redacted]"; }
    }

    /** Non-informative dispositions only; numeric, predicate and risk values have no representation. */
    @JsonPropertyOrder({"signal", "status", "reason"})
    public record SignalFact(Signal signal, Status status, Reason reason) {
        public SignalFact {
            require(signal != null && status != null && reason != null, "SIGNAL_FACT");
            boolean coherent = switch (status) {
                case UNAVAILABLE -> reason == Reason.NOT_ADMITTED || reason == Reason.NO_SOURCE;
                case UNTRUSTED -> reason == Reason.CLIENT_ASSERTION || reason == Reason.SYNTHETIC_INPUT;
                case UNSUPPORTED -> reason == Reason.METHODOLOGY_MISSING;
                case INVALID -> reason == Reason.INVALID_PROVENANCE || reason == Reason.INVALID_TIME;
            };
            require(coherent, "STATE_REASON");
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw invalid("UNKNOWN_FIELD"); }

        @Override
        public String toString() { return "SignalFact[redacted]"; }
    }

    private static void reference(String value) {
        require(value != null && OPAQUE_REFERENCE.matcher(value).matches(), "REFERENCE");
    }

    private static void time(Instant value) {
        require(value != null && !value.isBefore(MIN_TIME) && value.isBefore(MAX_TIME), "TIME");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw invalid(reason);
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("MERCHANT_CLAIM_" + reason);
    }
}
