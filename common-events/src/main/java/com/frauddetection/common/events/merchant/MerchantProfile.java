package com.frauddetection.common.events.merchant;

import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** Internal immutable projection value, not an admission proof or public DTO. Readers need independently authorized occurrence access. */
public record MerchantProfile(
        int schemaVersion,
        int computationVersion,
        ScopeClaim scope,
        OccurrenceClaim occurrence,
        Provenance provenance,
        WindowCount recent,
        WindowCount baseline
) {
    public static final int VERSION = 1;
    public static final int COMPUTATION_VERSION = 1;
    public static final int MAX_HISTORY_ROWS = 25_000;
    public static final int MAX_CANONICAL_BYTES = 4_096;
    public static final Duration MAX_TOTAL_WINDOW = Duration.ofDays(30);

    public MerchantProfile {
        require(schemaVersion == VERSION && computationVersion == COMPUTATION_VERSION, "VERSION");
        require(scope != null && occurrence != null && provenance != null && recent != null && baseline != null, "REQUIRED_FIELD");
        require(recent.end().equals(occurrence.eventTime()) && baseline.end().equals(recent.start())
                && Duration.between(baseline.start(), recent.end()).compareTo(MAX_TOTAL_WINDOW) <= 0, "WINDOW_BINDING");
        require(!provenance.evaluatedAt().isBefore(occurrence.eventTime()), "EVALUATION_TIME");
        if (recent.status() == Status.AVAILABLE || baseline.status() == Status.AVAILABLE) {
            require(provenance.coverageStart() != null && provenance.watermark() != null && provenance.retainedFrom() != null
                    && provenance.throughSequence() != null && provenance.throughSequence() >= occurrence.sourceSequence()
                    && !provenance.watermark().isBefore(occurrence.eventTime()) && provenance.historyCommitment() != null, "PROVENANCE");
            for (WindowCount window : new WindowCount[] {recent, baseline}) {
                if (window.status() == Status.AVAILABLE) {
                    require(!provenance.coverageStart().isAfter(window.start())
                            && !provenance.retainedFrom().isAfter(window.start()), "COVERAGE");
                }
            }
            require((recent.count() == null ? 0 : recent.count()) + (baseline.count() == null ? 0 : baseline.count()) <= MAX_HISTORY_ROWS,
                    "COUNT_LIMIT");
        }
    }

    public enum Status { AVAILABLE, UNAVAILABLE, UNSUPPORTED }
    public enum Reason { NONE, NOT_ADMITTED, MISSING_COVERAGE, SOURCE_GAP, RETENTION_EXPIRED, HISTORY_MISSING,
        CAPACITY_EXCEEDED, SCOPE_MISMATCH, OCCURRENCE_MISMATCH, WINDOW_MISMATCH, DATA_CONFLICT, INVALID_TIME }
    public enum UnsupportedFact {
        AMOUNTS_NOT_SUPPORTED, CUSTOMER_LINKAGE_NOT_SUPPORTED, CATEGORY_NOT_SUPPORTED,
        FRAUD_LABELS_NOT_SUPPORTED, POPULATION_NOT_SUPPORTED;
        public Status status() { return Status.UNSUPPORTED; }
    }

    public UnsupportedFact volume() { return UnsupportedFact.AMOUNTS_NOT_SUPPORTED; }
    public UnsupportedFact distinctCustomers() { return UnsupportedFact.CUSTOMER_LINKAGE_NOT_SUPPORTED; }
    public UnsupportedFact category() { return UnsupportedFact.CATEGORY_NOT_SUPPORTED; }
    public UnsupportedFact labels() { return UnsupportedFact.FRAUD_LABELS_NOT_SUPPORTED; }
    public UnsupportedFact populationDenominator() { return UnsupportedFact.POPULATION_NOT_SUPPORTED; }

    /** Diagnostic view excludes all identity references, payload commitments and history commitments. */
    public Diagnostics diagnostics() {
        return new Diagnostics(computationVersion, recent, baseline, volume(), distinctCustomers(), category(), labels(), populationDenominator());
    }

    public record Diagnostics(int computationVersion, WindowCount recent, WindowCount baseline,
                              UnsupportedFact volume, UnsupportedFact distinctCustomers, UnsupportedFact category,
                              UnsupportedFact labels, UnsupportedFact populationDenominator) { }

    public record WindowCount(Instant start, Instant end, Status status, Integer count, Reason reason) {
        public WindowCount {
            time(start);
            time(end);
            require(start.isBefore(end) && Duration.between(start, end).compareTo(MAX_TOTAL_WINDOW) <= 0, "WINDOW");
            require(status != null && reason != null && status != Status.UNSUPPORTED, "STATE");
            require(status == Status.AVAILABLE
                    ? count != null && count >= 0 && count <= MAX_HISTORY_ROWS && reason == Reason.NONE
                    : count == null && reason != Reason.NONE, "STATE");
        }
    }

    public record Provenance(Instant evaluatedAt, Instant retainedFrom, Instant coverageStart, Instant watermark,
                             Long throughSequence, String historyCommitment) {
        public Provenance {
            time(evaluatedAt);
            if (retainedFrom != null) { time(retainedFrom); }
            require((coverageStart == null) == (watermark == null) && (watermark == null) == (throughSequence == null), "PROVENANCE");
            if (watermark != null) {
                time(coverageStart);
                time(watermark);
                require(coverageStart.isBefore(watermark) && throughSequence >= 1, "PROVENANCE");
            }
            require(historyCommitment == null || Pattern.matches("[0-9a-f]{64}", historyCommitment), "HISTORY_COMMITMENT");
        }
        @Override public String toString() { return "MerchantProfileProvenance[redacted]"; }
    }

    /** Versioned deterministic bytes for internal identity only; never an authentication mechanism. */
    public byte[] canonicalBytes() {
        StringBuilder out = new StringBuilder(1_536);
        append(out, "merchant-profile/v1", schemaVersion, computationVersion, scope.issuerRef(), scope.institutionRef(),
                scope.sourceRef(), scope.sourceEpochRef(), scope.merchantRef(), scope.identityVersion(), occurrence.sourceEventRef(),
                occurrence.transactionRef(), occurrence.scoringOccurrenceRef(), occurrence.sourceSequence(), occurrence.eventTime(),
                occurrence.payloadCommitmentClaim(), provenance.evaluatedAt(), provenance.retainedFrom(), provenance.coverageStart(),
                provenance.watermark(), provenance.throughSequence(), provenance.historyCommitment());
        for (WindowCount window : new WindowCount[] {recent, baseline}) {
            append(out, window.start(), window.end(), window.status(), window.count(), window.reason());
        }
        append(out, volume(), distinctCustomers(), category(), labels(), populationDenominator());
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_CANONICAL_BYTES, "OUTPUT_LIMIT");
        return bytes;
    }

    private static void append(StringBuilder out, Object... values) {
        for (Object value : values) {
            String text = value == null ? null : value.toString();
            out.append(text == null ? -1 : text.getBytes(StandardCharsets.UTF_8).length).append(':');
            if (text != null) { out.append(text); }
        }
    }

    private static void time(Instant instant) {
        require(instant != null && !instant.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                && instant.isBefore(Instant.parse("2100-01-01T00:00:00Z")) && instant.getNano() % 1_000_000 == 0, "TIME");
    }
    private static void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalArgumentException("MERCHANT_PROFILE_" + reason); }
    }
    @Override public String toString() { return "MerchantProfile[redacted]"; }
}
