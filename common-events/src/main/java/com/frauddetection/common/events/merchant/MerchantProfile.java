package com.frauddetection.common.events.merchant;

import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.MerchantPopulationIdentity;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.PopulationBasis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Internal immutable projection value, not an admission proof or public DTO. Readers need independently authorized occurrence access. */
public record MerchantProfile(
        int schemaVersion,
        int computationVersion,
        ScopeClaim scope,
        OccurrenceClaim occurrence,
        Provenance provenance,
        WindowCount recent,
        WindowCount baseline,
        MerchantProfileFacts facts
) {
    public static final int VERSION = 3;
    public static final int COMPUTATION_VERSION = 1;
    public static final int MAX_HISTORY_ROWS = 25_000;
    public static final int MAX_CANONICAL_BYTES = 8_192;
    public static final Duration MAX_TOTAL_WINDOW = Duration.ofDays(30);

    public MerchantProfile {
        require(schemaVersion == VERSION && computationVersion == COMPUTATION_VERSION, "VERSION");
        require(scope != null && occurrence != null && provenance != null && recent != null && baseline != null && facts != null,
                "REQUIRED_FIELD");
        require(recent.end().equals(occurrence.eventTime()) && baseline.end().equals(recent.start())
                && Duration.between(baseline.start(), recent.end()).compareTo(MAX_TOTAL_WINDOW) <= 0, "WINDOW_BINDING");
        require(!provenance.evaluatedAt().isBefore(occurrence.eventTime()), "EVALUATION_TIME");
        validateFacts(scope, occurrence, provenance, recent, baseline, facts);
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

    /** Disabled callers without typed observations receive explicit non-informative fact states. */
    public MerchantProfile(int schemaVersion, int computationVersion, ScopeClaim scope, OccurrenceClaim occurrence,
                           Provenance provenance, WindowCount recent, WindowCount baseline) {
        this(schemaVersion, computationVersion, scope, occurrence, provenance, recent, baseline,
                MerchantProfileFacts.currentPath());
    }

    public enum Status { AVAILABLE, UNAVAILABLE, UNSUPPORTED }
    public enum Reason { NONE, NOT_ADMITTED, MISSING_COVERAGE, SOURCE_GAP, RETENTION_EXPIRED, HISTORY_MISSING,
        CAPACITY_EXCEEDED, SCOPE_MISMATCH, OCCURRENCE_MISMATCH, WINDOW_MISMATCH, DATA_CONFLICT, INVALID_TIME }
    public MerchantProfileFacts.Fact<MerchantProfileFacts.ActivityVolume> volume() { return facts.activityVolume(); }
    public MerchantProfileFacts.Fact<MerchantProfileFacts.CustomerLinkage> distinctCustomers() { return facts.customerLinkage(); }
    public MerchantProfileFacts.Fact<MerchantProfileFacts.CategoryClassification> category() { return facts.category(); }
    public MerchantProfileFacts.Fact<MerchantProfileFacts.MatureFraudLabels> labels() { return facts.matureFraudLabels(); }
    public MerchantProfileFacts.Fact<MerchantProfileFacts.CoveredPopulation> populationDenominator() { return facts.coveredPopulation(); }

    /** Structural pairing with a positive denominator only; fact provenance remains an unverified claim. */
    public boolean hasCompatibleFraudPopulationFacts() {
        return labels().status() == MerchantProfileFacts.Status.AVAILABLE
                && populationDenominator().status() == MerchantProfileFacts.Status.AVAILABLE
                && labels().value().populationIdentity().equals(populationDenominator().value().populationIdentity())
                && populationDenominator().value().eligibleTransactions() > 0;
    }

    /** Diagnostic view excludes all identity references, payload commitments and history commitments. */
    public Diagnostics diagnostics() {
        return new Diagnostics(computationVersion, recent, baseline, summary(volume()), summary(distinctCustomers()), summary(category()),
                summary(labels()), summary(populationDenominator()), summary(facts.institutionMerchantMapping()));
    }

    public record Diagnostics(int computationVersion, WindowCount recent, WindowCount baseline,
                              FactSummary volume, FactSummary distinctCustomers, FactSummary category,
                              FactSummary labels, FactSummary populationDenominator, FactSummary institutionIdentityMapping) { }

    public record FactSummary(MerchantProfileFacts.Status status, MerchantProfileFacts.Reason reason) { }

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
        MerchantCanonicalEncoding.append(out, "merchant-profile/v3", schemaVersion, computationVersion, scope.issuerRef(), scope.institutionRef(),
                scope.sourceRef(), scope.sourceEpochRef(), scope.merchantRef(), scope.identityVersion(), occurrence.sourceEventRef(),
                occurrence.transactionRef(), occurrence.scoringOccurrenceRef(), occurrence.sourceSequence(), occurrence.eventTime(),
                occurrence.payloadCommitmentClaim(), factDigest(facts.canonicalBytes()),
                provenance.evaluatedAt(), provenance.retainedFrom(), provenance.coverageStart(),
                provenance.watermark(), provenance.throughSequence(), provenance.historyCommitment());
        for (WindowCount window : new WindowCount[] {recent, baseline}) {
            MerchantCanonicalEncoding.append(out, window.start(), window.end(), window.status(), window.count(), window.reason());
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_CANONICAL_BYTES, "OUTPUT_LIMIT");
        return bytes;
    }

    private static void validateFacts(ScopeClaim scope, OccurrenceClaim occurrence, Provenance provenance,
                                      WindowCount recent, WindowCount baseline, MerchantProfileFacts facts) {
        for (MerchantProfileFacts.Fact<?> fact : new MerchantProfileFacts.Fact<?>[] {facts.activityVolume(), facts.category(),
                facts.customerLinkage(), facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping()}) {
            if (fact.status() == MerchantProfileFacts.Status.AVAILABLE) {
                require(!fact.provenance().observedAt().isAfter(provenance.evaluatedAt()), "FACT_PROVENANCE_TIME");
            }
        }
        if (facts.activityVolume().status() == MerchantProfileFacts.Status.AVAILABLE) {
            var volume = facts.activityVolume().value();
            require(recent.status() == Status.AVAILABLE && baseline.status() == Status.AVAILABLE
                    && volume.recent().from().equals(recent.start()) && volume.recent().until().equals(recent.end())
                    && volume.baseline().from().equals(baseline.start()) && volume.baseline().until().equals(baseline.end())
                    && volume.recent().transactionCount() == recent.count()
                    && volume.baseline().transactionCount() == baseline.count(), "ACTIVITY_BINDING");
            require(facts.activityVolume().provenance().sourceRef().equals(scope.sourceRef()), "ACTIVITY_SOURCE_SCOPE");
        }
        if (facts.category().status() == MerchantProfileFacts.Status.AVAILABLE) {
            require(!facts.category().value().effectiveAt().isAfter(occurrence.eventTime()), "CATEGORY_EFFECTIVE_TIME");
        }
        if (facts.customerLinkage().status() == MerchantProfileFacts.Status.AVAILABLE) {
            var linkage = facts.customerLinkage().value();
            require(linkage.windowStart().equals(recent.start()) && linkage.windowEnd().equals(recent.end())
                    && facts.customerLinkage().provenance().sourceRef().equals(scope.sourceRef()), "CUSTOMER_LINKAGE_SCOPE");
            if (facts.activityVolume().status() == MerchantProfileFacts.Status.AVAILABLE) {
                require(linkage.distinctCustomers() <= facts.activityVolume().value().recent().transactionCount(),
                        "CUSTOMER_LINKAGE_COUNT");
            }
        }
        if (facts.matureFraudLabels().status() == MerchantProfileFacts.Status.AVAILABLE) {
            var labels = facts.matureFraudLabels().value();
            require(!labels.matureThrough().isAfter(provenance.evaluatedAt()), "LABELS_WINDOW");
            validatePopulationIdentity(labels.populationIdentity(), scope, recent, provenance, facts);
            if (labels.populationIdentity().basis() == PopulationBasis.SOURCE_OBSERVATION_COHORT) {
                require(recent.status() == Status.AVAILABLE
                                && (long) labels.confirmedFraud() + labels.confirmedLegitimate() <= recent.count(),
                        "LABEL_OBSERVATION_COHORT");
            } else {
                require(facts.coveredPopulation().status() == MerchantProfileFacts.Status.AVAILABLE,
                        "LABEL_POPULATION_REQUIRED");
            }
        }
        if (facts.coveredPopulation().status() == MerchantProfileFacts.Status.AVAILABLE) {
            var population = facts.coveredPopulation().value();
            validatePopulationIdentity(population.populationIdentity(), scope, recent, provenance, facts);
            if (population.populationIdentity().basis() == PopulationBasis.SOURCE_OBSERVATION_COHORT) {
                require(recent.status() == Status.AVAILABLE && population.eligibleTransactions() <= recent.count(),
                        "POPULATION_OBSERVATION_COHORT");
            }
            if (facts.matureFraudLabels().status() == MerchantProfileFacts.Status.AVAILABLE) {
                var labels = facts.matureFraudLabels().value();
                require(labels.populationIdentity().equals(population.populationIdentity()), "POPULATION_IDENTITY_MISMATCH");
                require((long) labels.confirmedFraud() + labels.confirmedLegitimate() <= population.eligibleTransactions(),
                        "LABEL_POPULATION");
            }
        }
        if (facts.institutionMerchantMapping().status() == MerchantProfileFacts.Status.AVAILABLE) {
            var mapping = facts.institutionMerchantMapping().value();
            require(mapping.issuerRef().equals(scope.issuerRef())
                    && mapping.institutionRef().equals(scope.institutionRef())
                    && mapping.sourceIdentityVersion() == scope.identityVersion()
                    && mapping.sourceRef().equals(scope.sourceRef())
                    && mapping.sourceEpochRef().equals(scope.sourceEpochRef())
                    && mapping.merchantRef().equals(scope.merchantRef())
                    && !recent.start().isBefore(mapping.validFrom())
                    && !recent.end().isAfter(mapping.validUntil())
                    && !occurrence.eventTime().isBefore(mapping.validFrom())
                    && occurrence.eventTime().isBefore(mapping.validUntil()), "IDENTITY_MAPPING_SCOPE");
        }
    }

    private static void validatePopulationIdentity(MerchantPopulationIdentity identity, ScopeClaim scope,
                                                   WindowCount recent, Provenance provenance,
                                                   MerchantProfileFacts facts) {
        require(identity.issuerRef().equals(scope.issuerRef())
                        && identity.institutionRef().equals(scope.institutionRef())
                        && identity.sourceRef().equals(scope.sourceRef())
                        && identity.sourceEpochRef().equals(scope.sourceEpochRef())
                        && identity.merchantRef().equals(scope.merchantRef())
                        && identity.identityVersion() == scope.identityVersion()
                        && identity.windowStart().equals(recent.start()) && identity.windowEnd().equals(recent.end())
                        && !identity.asOf().isAfter(provenance.evaluatedAt()),
                "POPULATION_IDENTITY_SCOPE");
        if (identity.basis() == PopulationBasis.SOURCE_OBSERVATION_COHORT) {
            require(recent.status() == Status.AVAILABLE && provenance.historyCommitment() != null
                            && identity.cohortCommitment().equals(provenance.historyCommitment()),
                    "POPULATION_OBSERVATION_COMMITMENT");
        } else {
            require(facts.institutionMerchantMapping().status() == MerchantProfileFacts.Status.AVAILABLE,
                    "POPULATION_MAPPING_REQUIRED");
            var mapping = facts.institutionMerchantMapping().value();
            require(identity.institutionMerchantRef().equals(mapping.institutionMerchantRef())
                            && identity.mappingVersion() == mapping.mappingVersion()
                            && identity.sourceSetCommitment().equals(mapping.sourceSetCommitment()),
                    "POPULATION_MAPPING_SCOPE");
        }
    }

    private static FactSummary summary(MerchantProfileFacts.Fact<?> fact) {
        return new FactSummary(fact.status(), fact.reason());
    }

    private static String factDigest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MERCHANT_PROFILE_DIGEST_UNAVAILABLE");
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
