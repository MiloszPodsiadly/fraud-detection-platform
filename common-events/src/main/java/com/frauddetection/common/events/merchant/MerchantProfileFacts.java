package com.frauddetection.common.events.merchant;

import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.model.SupportedCurrencyContract;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** Internal typed fact envelope. Its provenance is a claim and never replaces independent admission. */
public record MerchantProfileFacts(
        int schemaVersion,
        Fact<ActivityVolume> activityVolume,
        Fact<CategoryClassification> category,
        Fact<CustomerLinkage> customerLinkage,
        Fact<MatureFraudLabels> matureFraudLabels,
        Fact<CoveredPopulation> coveredPopulation,
        Fact<InstitutionMerchantMapping> institutionMerchantMapping
) {
    public static final int VERSION = 1;
    public static final int MAX_CANONICAL_BYTES = 4_096;
    private static final Pattern REFERENCE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MCC = Pattern.compile("[0-9]{4}");
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z0-9._:-]{1,64}");
    private static final long MAX_POPULATION = 1_000_000_000_000L;

    public MerchantProfileFacts {
        require(schemaVersion == VERSION, "VERSION");
        require(activityVolume != null && category != null && customerLinkage != null && matureFraudLabels != null
                && coveredPopulation != null && institutionMerchantMapping != null, "REQUIRED_FACT");
        requireOrigin(activityVolume, Origin.TRANSACTION_HISTORY);
        requireOrigin(category, Origin.MCC_TAXONOMY);
        requireOrigin(customerLinkage, Origin.TRANSACTION_HISTORY);
        requireOrigin(matureFraudLabels, Origin.ADJUDICATED_FEEDBACK);
        requireOrigin(coveredPopulation, Origin.POPULATION_COVERAGE);
        requireOrigin(institutionMerchantMapping, Origin.INSTITUTION_IDENTITY_MAPPING);
        if (category.status() == Status.AVAILABLE) {
            requirePolicyVersion(category, ((CategoryClassification) category.value()).taxonomyVersion());
        }
        if (customerLinkage.status() == Status.AVAILABLE) {
            requirePolicyVersion(customerLinkage, ((CustomerLinkage) customerLinkage.value()).customerIdentityVersion());
        }
        if (matureFraudLabels.status() == Status.AVAILABLE) {
            requirePolicyVersion(matureFraudLabels, ((MatureFraudLabels) matureFraudLabels.value()).labelPolicyVersion());
        }
        if (coveredPopulation.status() == Status.AVAILABLE) {
            requirePolicyVersion(coveredPopulation, ((CoveredPopulation) coveredPopulation.value()).populationDefinitionVersion());
        }
        if (institutionMerchantMapping.status() == Status.AVAILABLE) {
            requirePolicyVersion(institutionMerchantMapping,
                    ((InstitutionMerchantMapping) institutionMerchantMapping.value()).mappingVersion());
        }
    }

    /** Current transaction path: values exist as client claims, while the other domains lack their required source. */
    public static MerchantProfileFacts currentPath() {
        return new MerchantProfileFacts(VERSION,
                Fact.untrusted(Reason.CLIENT_ASSERTION),
                Fact.unsupported(Reason.TAXONOMY_NOT_VERIFIED),
                Fact.untrusted(Reason.CLIENT_ASSERTION),
                Fact.unsupported(Reason.LABEL_MATURITY_POLICY_MISSING),
                Fact.missing(Reason.POPULATION_COVERAGE_MISSING),
                Fact.missing(Reason.INSTITUTION_IDENTITY_MAPPING_REQUIRED));
    }

    public byte[] canonicalBytes() {
        StringBuilder out = new StringBuilder(1_024);
        MerchantCanonicalEncoding.append(out, "merchant-profile-facts/v1", schemaVersion);
        appendFact(out, activityVolume);
        appendFact(out, category);
        appendFact(out, customerLinkage);
        appendFact(out, matureFraudLabels);
        appendFact(out, coveredPopulation);
        appendFact(out, institutionMerchantMapping);
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_CANONICAL_BYTES, "CANONICAL_SIZE");
        return bytes;
    }

    @Override
    public String toString() {
        return "MerchantProfileFacts[redacted]";
    }

    public enum Status { AVAILABLE, MISSING, UNTRUSTED, UNSUPPORTED }
    public enum Reason { NONE, CLIENT_ASSERTION, SOURCE_NOT_ADMITTED, SOURCE_MISSING, TAXONOMY_NOT_VERIFIED,
        LABEL_MATURITY_POLICY_MISSING, POPULATION_COVERAGE_MISSING, INSTITUTION_IDENTITY_MAPPING_REQUIRED,
        METHOD_NOT_VALIDATED }
    public enum Origin { TRANSACTION_HISTORY, MCC_TAXONOMY, ADJUDICATED_FEEDBACK, POPULATION_COVERAGE,
        INSTITUTION_IDENTITY_MAPPING }

    /** `AVAILABLE` means an adapter asserted completeness; a verifier must still admit its exact provenance. */
    public record Fact<T extends Value>(Status status, Reason reason, Provenance provenance, T value) {
        public Fact {
            require(status != null && reason != null, "FACT_STATE");
            boolean available = status == Status.AVAILABLE;
            require(available
                    ? reason == Reason.NONE && provenance != null && value != null
                    : reason != Reason.NONE && provenance == null && value == null, "FACT_STATE");
            require(switch (status) {
                case AVAILABLE -> reason == Reason.NONE;
                case MISSING -> reason == Reason.SOURCE_MISSING || reason == Reason.POPULATION_COVERAGE_MISSING
                        || reason == Reason.INSTITUTION_IDENTITY_MAPPING_REQUIRED;
                case UNTRUSTED -> reason == Reason.CLIENT_ASSERTION || reason == Reason.SOURCE_NOT_ADMITTED;
                case UNSUPPORTED -> reason == Reason.TAXONOMY_NOT_VERIFIED || reason == Reason.LABEL_MATURITY_POLICY_MISSING
                        || reason == Reason.METHOD_NOT_VALIDATED;
            }, "FACT_STATE");
        }

        public static <T extends Value> Fact<T> available(T value, Provenance provenance) {
            return new Fact<>(Status.AVAILABLE, Reason.NONE, provenance, value);
        }

        public static <T extends Value> Fact<T> missing(Reason reason) {
            return new Fact<>(Status.MISSING, reason, null, null);
        }

        public static <T extends Value> Fact<T> untrusted(Reason reason) {
            return new Fact<>(Status.UNTRUSTED, reason, null, null);
        }

        public static <T extends Value> Fact<T> unsupported(Reason reason) {
            return new Fact<>(Status.UNSUPPORTED, reason, null, null);
        }

        @Override
        public String toString() {
            return "MerchantFact[" + status + "," + reason + "]";
        }
    }

    /** Opaque provenance references are identifiers, not signatures or standalone authorization. */
    public record Provenance(Origin origin, String sourceRef, int sourceVersion, int policyVersion,
                             Instant observedAt, String evidenceCommitment) {
        public Provenance {
            require(origin != null, "PROVENANCE_ORIGIN");
            reference(sourceRef, "PROVENANCE_SOURCE");
            require(sourceVersion >= 1 && sourceVersion <= 65_535
                    && policyVersion >= 1 && policyVersion <= 65_535, "PROVENANCE_VERSION");
            time(observedAt, "PROVENANCE_TIME");
            reference(evidenceCommitment, "PROVENANCE_COMMITMENT");
        }

        @Override
        public String toString() {
            return "MerchantFactProvenance[redacted]";
        }
    }

    public sealed interface Value permits ActivityVolume, CategoryClassification, CustomerLinkage,
            MatureFraudLabels, CoveredPopulation, InstitutionMerchantMapping { }

    /** Recent and baseline totals must use the same currency and adjacent half-open windows. */
    public record ActivityVolume(ActivityWindow recent, ActivityWindow baseline) implements Value {
        public ActivityVolume {
            require(recent != null && baseline != null && baseline.until().equals(recent.from()), "ACTIVITY_WINDOWS");
            require(recent.totalAmount().currency().equals(baseline.totalAmount().currency()), "ACTIVITY_CURRENCY");
            require((long) recent.transactionCount() + baseline.transactionCount() <= MerchantProfile.MAX_HISTORY_ROWS,
                    "ACTIVITY_COUNT");
        }

        @Override public String toString() { return "MerchantActivityVolume[redacted]"; }
    }

    public record ActivityWindow(Instant from, Instant until, int transactionCount, Money totalAmount) {
        public ActivityWindow {
            time(from, "ACTIVITY_TIME");
            time(until, "ACTIVITY_TIME");
            require(from.isBefore(until) && Duration.between(from, until).compareTo(MerchantProfile.MAX_TOTAL_WINDOW) <= 0,
                    "ACTIVITY_WINDOW");
            require(transactionCount >= 0 && transactionCount <= MerchantProfile.MAX_HISTORY_ROWS, "ACTIVITY_COUNT");
            validateMoney(totalAmount);
            require((transactionCount == 0) == (totalAmount.amount().signum() == 0), "ACTIVITY_TOTAL");
        }

        @Override public String toString() { return "MerchantActivityWindow[redacted]"; }
    }

    public record CategoryClassification(String mcc, String categoryCode, int taxonomyVersion,
                                         Instant effectiveAt) implements Value {
        public CategoryClassification {
            require(mcc != null && MCC.matcher(mcc).matches(), "MCC");
            require(categoryCode != null && CATEGORY_CODE.matcher(categoryCode).matches(), "CATEGORY_CODE");
            require(taxonomyVersion >= 1 && taxonomyVersion <= 65_535, "TAXONOMY_VERSION");
            time(effectiveAt, "CATEGORY_TIME");
        }

        @Override public String toString() { return "MerchantCategoryClassification[redacted]"; }
    }

    /** The count is source-scoped and tied to the version of its customer pseudonym key. */
    public record CustomerLinkage(Instant windowStart, Instant windowEnd, int distinctCustomers,
                                  int customerIdentityVersion) implements Value {
        public CustomerLinkage {
            time(windowStart, "CUSTOMER_WINDOW");
            time(windowEnd, "CUSTOMER_WINDOW");
            require(windowStart.isBefore(windowEnd)
                    && Duration.between(windowStart, windowEnd).compareTo(MerchantProfile.MAX_TOTAL_WINDOW) <= 0, "CUSTOMER_WINDOW");
            require(distinctCustomers >= 0 && distinctCustomers <= MerchantProfile.MAX_HISTORY_ROWS, "CUSTOMER_COUNT");
            require(customerIdentityVersion >= 1 && customerIdentityVersion <= 65_535, "CUSTOMER_IDENTITY_VERSION");
        }

        @Override public String toString() { return "MerchantCustomerLinkage[redacted]"; }
    }

    /** Only mature, resolved analyst outcomes count; unresolved and pending labels are excluded. */
    public record MatureFraudLabels(Instant windowStart, Instant windowEnd, int confirmedFraud,
                                    int confirmedLegitimate, Instant matureThrough,
                                    int labelPolicyVersion) implements Value {
        public MatureFraudLabels {
            time(windowStart, "LABEL_WINDOW");
            time(windowEnd, "LABEL_WINDOW");
            time(matureThrough, "LABEL_MATURITY");
            require(windowStart.isBefore(windowEnd)
                    && Duration.between(windowStart, windowEnd).compareTo(MerchantProfile.MAX_TOTAL_WINDOW) <= 0, "LABEL_WINDOW");
            require(!matureThrough.isBefore(windowEnd), "LABEL_MATURITY");
            require(confirmedFraud >= 0 && confirmedLegitimate >= 0
                    && (long) confirmedFraud + confirmedLegitimate <= MerchantProfile.MAX_HISTORY_ROWS, "LABEL_COUNT");
            require(labelPolicyVersion >= 1 && labelPolicyVersion <= 65_535, "LABEL_POLICY_VERSION");
        }

        @Override public String toString() { return "MerchantMatureFraudLabels[redacted]"; }
    }

    /** Population counts require a complete, versioned denominator definition for this exact window. */
    public record CoveredPopulation(Instant windowStart, Instant windowEnd, long eligibleTransactions,
                                    int populationDefinitionVersion) implements Value {
        public CoveredPopulation {
            time(windowStart, "POPULATION_WINDOW");
            time(windowEnd, "POPULATION_WINDOW");
            require(windowStart.isBefore(windowEnd)
                    && Duration.between(windowStart, windowEnd).compareTo(MerchantProfile.MAX_TOTAL_WINDOW) <= 0, "POPULATION_WINDOW");
            require(eligibleTransactions >= 0 && eligibleTransactions <= MAX_POPULATION, "POPULATION_COUNT");
            require(populationDefinitionVersion >= 1 && populationDefinitionVersion <= 65_535, "POPULATION_VERSION");
        }

        @Override public String toString() { return "MerchantCoveredPopulation[redacted]"; }
    }

    /** Cross-source identity is a versioned, effective-dated mapping, never raw-ID or pseudonym equality. */
    public record InstitutionMerchantMapping(String institutionMerchantRef, int sourceIdentityVersion,
                                             int mappingVersion, Instant validFrom, Instant validUntil) implements Value {
        public InstitutionMerchantMapping {
            reference(institutionMerchantRef, "INSTITUTION_MERCHANT_REFERENCE");
            require(sourceIdentityVersion >= 1 && sourceIdentityVersion <= 65_535
                    && mappingVersion >= 1 && mappingVersion <= 65_535, "IDENTITY_MAPPING_VERSION");
            time(validFrom, "IDENTITY_MAPPING_TIME");
            time(validUntil, "IDENTITY_MAPPING_TIME");
            require(validFrom.isBefore(validUntil), "IDENTITY_MAPPING_TIME");
        }

        @Override public String toString() { return "InstitutionMerchantMapping[redacted]"; }
    }

    private static void requireOrigin(Fact<? extends Value> fact, Origin expected) {
        if (fact.status() == Status.AVAILABLE) {
            require(fact.provenance().origin() == expected, "PROVENANCE_ORIGIN");
        }
    }

    private static void requirePolicyVersion(Fact<? extends Value> fact, int expected) {
        if (fact.status() == Status.AVAILABLE) {
            require(fact.provenance().policyVersion() == expected, "PROVENANCE_POLICY_VERSION");
        }
    }

    private static void appendFact(StringBuilder out, Fact<? extends Value> fact) {
        MerchantCanonicalEncoding.append(out, fact.status(), fact.reason());
        Provenance provenance = fact.provenance();
        MerchantCanonicalEncoding.append(out, provenance == null ? null : provenance.origin(), provenance == null ? null : provenance.sourceRef(),
                provenance == null ? null : provenance.sourceVersion(), provenance == null ? null : provenance.policyVersion(),
                provenance == null ? null : provenance.observedAt(),
                provenance == null ? null : provenance.evidenceCommitment());
        if (fact.value() == null) {
            MerchantCanonicalEncoding.append(out, (Object) null);
        } else if (fact.value() instanceof ActivityVolume value) {
            MerchantCanonicalEncoding.append(out, "ACTIVITY", value.recent().from(), value.recent().until(), value.recent().transactionCount(),
                    decimal(value.recent().totalAmount().amount()), value.recent().totalAmount().currency(),
                    value.baseline().from(), value.baseline().until(), value.baseline().transactionCount(),
                    decimal(value.baseline().totalAmount().amount()), value.baseline().totalAmount().currency());
        } else if (fact.value() instanceof CategoryClassification value) {
            MerchantCanonicalEncoding.append(out, "CATEGORY", value.mcc(), value.categoryCode(), value.taxonomyVersion(), value.effectiveAt());
        } else if (fact.value() instanceof CustomerLinkage value) {
            MerchantCanonicalEncoding.append(out, "CUSTOMERS", value.windowStart(), value.windowEnd(), value.distinctCustomers(), value.customerIdentityVersion());
        } else if (fact.value() instanceof MatureFraudLabels value) {
            MerchantCanonicalEncoding.append(out, "LABELS", value.windowStart(), value.windowEnd(), value.confirmedFraud(), value.confirmedLegitimate(),
                    value.matureThrough(), value.labelPolicyVersion());
        } else if (fact.value() instanceof CoveredPopulation value) {
            MerchantCanonicalEncoding.append(out, "POPULATION", value.windowStart(), value.windowEnd(), value.eligibleTransactions(),
                    value.populationDefinitionVersion());
        } else if (fact.value() instanceof InstitutionMerchantMapping value) {
            MerchantCanonicalEncoding.append(out, "IDENTITY_MAPPING", value.institutionMerchantRef(), value.sourceIdentityVersion(), value.mappingVersion(),
                    value.validFrom(), value.validUntil());
        } else {
            throw invalid("VALUE_TYPE");
        }
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void validateMoney(Money money) {
        require(money != null && money.amount() != null, "AMOUNT");
        BigDecimal amount = money.amount();
        require(amount.signum() >= 0 && amount.scale() >= 0 && amount.scale() <= 2 && amount.precision() <= 20, "AMOUNT");
        require(money.currency() != null && money.currency().matches(SupportedCurrencyContract.SUPPORTED_CURRENCY_PATTERN), "CURRENCY");
        require(SupportedCurrencyContract.isSupported(money.currency()), "CURRENCY");
    }

    private static void time(Instant value, String reason) {
        require(value != null && !value.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                && value.isBefore(Instant.parse("2100-01-01T00:00:00Z")) && value.getNano() % 1_000_000 == 0, reason);
    }

    private static void reference(String value, String reason) {
        require(value != null && REFERENCE.matcher(value).matches(), reason);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) { throw invalid(reason); }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("MERCHANT_FACTS_" + reason);
    }

}
