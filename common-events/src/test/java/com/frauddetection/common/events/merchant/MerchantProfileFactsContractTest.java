package com.frauddetection.common.events.merchant;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.merchant.MerchantProfile.Provenance;
import com.frauddetection.common.events.merchant.MerchantProfile.Reason;
import com.frauddetection.common.events.merchant.MerchantProfile.Status;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.ActivityVolume;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.ActivityWindow;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.CategoryClassification;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.CoveredPopulation;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.CustomerLinkage;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.Fact;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.InstitutionMerchantMapping;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.MatureFraudLabels;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.Origin;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.common.events.model.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantProfileFactsContractTest {
    private static final Instant TIME = Instant.parse("2027-01-15T10:00:00Z");
    private static final Instant EVALUATED = TIME.plusSeconds(120);
    private static final ScopeClaim SCOPE = new ScopeClaim(token('a'), token('b'), token('c'), token('d'), token('e'), 1);
    private static final OccurrenceClaim OCCURRENCE = new OccurrenceClaim(token('f'), token('1'), token('2'), 10, TIME, token('3'));

    @Test
    void missingZeroAndUntrustedValuesRemainDistinct() {
        MerchantProfileFacts current = MerchantProfileFacts.currentPath();
        Fact<ActivityVolume> missing = Fact.missing(MerchantProfileFacts.Reason.SOURCE_MISSING);
        Fact<ActivityVolume> zero = Fact.available(volume(0, "0.00", 0, "0"),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1));

        MerchantProfile absentProfile = profile(withActivity(current, missing), 0, 0);
        MerchantProfile zeroProfile = profile(withActivity(current, zero), 0, 0);

        assertThat(absentProfile.volume().status()).isEqualTo(MerchantProfileFacts.Status.MISSING);
        assertThat(absentProfile.volume().value()).isNull();
        assertThat(zeroProfile.volume().status()).isEqualTo(MerchantProfileFacts.Status.AVAILABLE);
        assertThat(zeroProfile.volume().value().recent().transactionCount()).isZero();
        assertThat(zeroProfile.volume().value().recent().totalAmount().amount()).isEqualByComparingTo("0");
        assertThat(absentProfile.canonicalBytes()).isNotEqualTo(zeroProfile.canonicalBytes());
        MerchantProfileFacts normalizedZero = withActivity(current, Fact.available(volume(0, "0", 0, "0.00"),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1)));
        assertThat(normalizedZero.canonicalBytes()).isEqualTo(withActivity(current, zero).canonicalBytes());
    }

    @Test
    void amountCurrencyAndMccUseBoundedExistingTransactionSemantics() {
        assertThatThrownBy(() -> new ActivityWindow(TIME.minusSeconds(3600), TIME, 1,
                new Money(new BigDecimal("1.001"), "PLN"))).hasMessage("MERCHANT_FACTS_AMOUNT");
        assertThatThrownBy(() -> new ActivityWindow(TIME.minusSeconds(3600), TIME, 1,
                new Money(new BigDecimal("1.00"), "usd"))).hasMessage("MERCHANT_FACTS_CURRENCY");
        assertThatThrownBy(() -> new ActivityWindow(TIME.minusSeconds(3600), TIME, 1,
                new Money(new BigDecimal("123456789012345678901"), "PLN"))).hasMessage("MERCHANT_FACTS_AMOUNT");
        assertThatThrownBy(() -> new CategoryClassification("57AB", "GROCERY", 1, TIME.minusSeconds(1)))
                .hasMessage("MERCHANT_FACTS_MCC");
        assertThatThrownBy(() -> new CategoryClassification("5411", "x".repeat(65), 1, TIME.minusSeconds(1)))
                .hasMessage("MERCHANT_FACTS_CATEGORY_CODE");
        assertThatThrownBy(() -> new ActivityVolume(
                new ActivityWindow(TIME.minusSeconds(3600), TIME, 25_000, new Money(new BigDecimal("1.00"), "PLN")),
                new ActivityWindow(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 1,
                        new Money(new BigDecimal("1.00"), "PLN")))).hasMessage("MERCHANT_FACTS_ACTIVITY_COUNT");
        assertThatThrownBy(() -> new MatureFraudLabels(TIME.minusSeconds(3600), TIME, 25_000, 1,
                TIME.plusSeconds(60), 1)).hasMessage("MERCHANT_FACTS_LABEL_COUNT");
        assertThatThrownBy(() -> new CoveredPopulation(TIME.minusSeconds(3600), TIME, 1_000_000_000_001L, 1))
                .hasMessage("MERCHANT_FACTS_POPULATION_COUNT");
        assertThatThrownBy(() -> new MatureFraudLabels(TIME.minusSeconds(3600), TIME, 1, 1,
                TIME.minusMillis(1), 1)).hasMessage("MERCHANT_FACTS_LABEL_MATURITY");
    }

    @Test
    void typedFactsRequireExactSourceIdentityWindowAndPolicyVersions() {
        MerchantProfileFacts current = MerchantProfileFacts.currentPath();
        Fact<ActivityVolume> foreignSource = Fact.available(volume(2, "12.00", 5, "20.00"),
                provenance(Origin.TRANSACTION_HISTORY, token('9'), 1));
        assertThatThrownBy(() -> profile(withActivity(current, foreignSource), 2, 5))
                .hasMessage("MERCHANT_PROFILE_ACTIVITY_SOURCE_SCOPE");

        InstitutionMerchantMapping wrongEpoch = new InstitutionMerchantMapping(token('8'), 2, 4,
                TIME.minusSeconds(60), TIME.plusSeconds(60));
        MerchantProfileFacts wrongMapping = factsWithMapping(current, Fact.available(wrongEpoch,
                provenance(Origin.INSTITUTION_IDENTITY_MAPPING, token('7'), 4)));
        assertThatThrownBy(() -> profile(wrongMapping, 0, 0)).hasMessage("MERCHANT_PROFILE_IDENTITY_MAPPING_SCOPE");

        assertThatThrownBy(() -> new MerchantProfileFacts(MerchantProfileFacts.VERSION, current.activityVolume(),
                Fact.available(new CategoryClassification("5411", "GROCERY", 2, TIME.minusSeconds(1)),
                        provenance(Origin.MCC_TAXONOMY, token('6'), 3)), current.customerLinkage(), current.matureFraudLabels(),
                current.coveredPopulation(), current.institutionMerchantMapping()))
                .hasMessage("MERCHANT_FACTS_PROVENANCE_POLICY_VERSION");
    }

    @Test
    void availableFactsMustMatchTheirProfileWindowsAndKnowledgeCutoff() {
        MerchantProfileFacts valid = availableFacts();
        MerchantProfile original = profile(valid, 2, 5);
        assertThat(original.facts().institutionMerchantMapping().value().mappingVersion()).isEqualTo(4);

        MerchantProfileFacts futureCategory = withCategory(valid, Fact.available(
                new CategoryClassification("5411", "GROCERY", 3, TIME.plusSeconds(1)),
                provenance(Origin.MCC_TAXONOMY, token('6'), 3)));
        assertThatThrownBy(() -> profile(futureCategory, 2, 5)).hasMessage("MERCHANT_PROFILE_CATEGORY_EFFECTIVE_TIME");

        MerchantProfileFacts mismatchedVolume = withActivity(valid, Fact.available(volume(1, "12.00", 5, "20.00"),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1)));
        assertThatThrownBy(() -> profile(mismatchedVolume, 2, 5)).hasMessage("MERCHANT_PROFILE_ACTIVITY_BINDING");

        MerchantProfileFacts futureKnowledge = withActivity(valid, Fact.available(volume(2, "12.00", 5, "20.00"),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1, EVALUATED.plusMillis(1))));
        assertThatThrownBy(() -> profile(futureKnowledge, 2, 5)).hasMessage("MERCHANT_PROFILE_FACT_PROVENANCE_TIME");
    }

    @Test
    void identityRotationAndCrossSourceMappingAreExplicitAndVersioned() {
        MerchantProfile sourceScoped = profile(MerchantProfileFacts.currentPath(), 0, 0);
        ScopeClaim rotated = new ScopeClaim(SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), token('8'),
                SCOPE.merchantRef(), 2);
        MerchantProfile newEpoch = profile(rotated, MerchantProfileFacts.currentPath(), 0, 0);
        ScopeClaim otherSource = new ScopeClaim(SCOPE.issuerRef(), SCOPE.institutionRef(), token('9'), SCOPE.sourceEpochRef(),
                SCOPE.merchantRef(), SCOPE.identityVersion());
        MerchantProfile samePseudonymOtherSource = profile(otherSource, MerchantProfileFacts.currentPath(), 0, 0);

        assertThat(sourceScoped.scope().sourceEpochRef()).isNotEqualTo(newEpoch.scope().sourceEpochRef());
        assertThat(sourceScoped.scope().identityVersion()).isNotEqualTo(newEpoch.scope().identityVersion());
        assertThat(sourceScoped.canonicalBytes()).isNotEqualTo(newEpoch.canonicalBytes());
        assertThat(sourceScoped.scope().merchantRef()).isEqualTo(samePseudonymOtherSource.scope().merchantRef());
        assertThat(sourceScoped.canonicalBytes()).isNotEqualTo(samePseudonymOtherSource.canonicalBytes());
        assertThat(sourceScoped.facts().institutionMerchantMapping().reason())
                .isEqualTo(MerchantProfileFacts.Reason.INSTITUTION_IDENTITY_MAPPING_REQUIRED);
        assertThat(newEpoch.facts().institutionMerchantMapping().status()).isEqualTo(MerchantProfileFacts.Status.MISSING);
    }

    @Test
    void versionBoundsCanonicalBytesAndDiagnosticsDoNotExposeFactEvidence() {
        MerchantProfileFacts facts = availableFacts();
        byte[] canonical = facts.canonicalBytes();
        assertThat(canonical.length).isLessThanOrEqualTo(MerchantProfileFacts.MAX_CANONICAL_BYTES);
        assertThat(facts.canonicalBytes()).isEqualTo(canonical);
        assertThat(facts.toString()).doesNotContain(token('7'), token('8'));

        MerchantProfile profile = profile(facts, 2, 5);
        String diagnostics = profile.diagnostics().toString();
        assertThat(diagnostics).doesNotContain(token('6'), token('7'), token('8'));
        assertThat(profile.toString()).doesNotContain("5411", "GROCERY", token('7'), token('8'));
        assertThat(profile.canonicalBytes().length).isLessThanOrEqualTo(MerchantProfile.MAX_CANONICAL_BYTES);
        assertThatThrownBy(() -> new MerchantProfileFacts(2, facts.activityVolume(), facts.category(), facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping()))
                .hasMessage("MERCHANT_FACTS_VERSION");
        assertThatThrownBy(() -> new MerchantProfileFacts.Provenance(Origin.MCC_TAXONOMY, "raw-taxonomy-id", 1, 1, TIME, token('4')))
                .hasMessage("MERCHANT_FACTS_PROVENANCE_SOURCE");
    }

    @Test
    void typedFactsCannotEnterPublicScoredEventOrEngineIntelligenceV2Shapes() {
        assertThat(recordTypeGraph(TransactionScoredEvent.class)).doesNotContain(MerchantProfile.class, MerchantProfileFacts.class);
        assertThat(recordTypeGraph(EngineIntelligenceSummary.class)).doesNotContain(MerchantProfile.class, MerchantProfileFacts.class);
    }

    private static MerchantProfileFacts availableFacts() {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION,
                Fact.available(volume(2, "12.00", 5, "20.00"), provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1)),
                Fact.available(new CategoryClassification("5411", "GROCERY", 3, TIME.minusSeconds(60)),
                        provenance(Origin.MCC_TAXONOMY, token('6'), 3)),
                Fact.available(new CustomerLinkage(TIME.minusSeconds(3600), TIME, 1, 2),
                        provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 2)),
                Fact.available(new MatureFraudLabels(TIME.minusSeconds(3600), TIME, 1, 3, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(TIME.minusSeconds(3600), TIME, 20, 4),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4)),
                Fact.available(new InstitutionMerchantMapping(token('8'), 1, 4, TIME.minusSeconds(60), TIME.plusSeconds(60)),
                        provenance(Origin.INSTITUTION_IDENTITY_MAPPING, token('7'), 4)));
    }

    private static ActivityVolume volume(int recentCount, String recentAmount, int baselineCount, String baselineAmount) {
        return new ActivityVolume(new ActivityWindow(TIME.minusSeconds(3600), TIME, recentCount,
                new Money(new BigDecimal(recentAmount), "PLN")),
                new ActivityWindow(TIME.minusSeconds(7200), TIME.minusSeconds(3600), baselineCount,
                        new Money(new BigDecimal(baselineAmount), "PLN")));
    }

    private static MerchantProfileFacts.Provenance provenance(Origin origin, String sourceRef, int policyVersion) {
        return provenance(origin, sourceRef, policyVersion, TIME.plusSeconds(30));
    }

    private static MerchantProfileFacts.Provenance provenance(Origin origin, String sourceRef, int policyVersion, Instant observedAt) {
        return new MerchantProfileFacts.Provenance(origin, sourceRef, 1, policyVersion, observedAt, token('3'));
    }

    private static MerchantProfileFacts withActivity(MerchantProfileFacts facts, Fact<ActivityVolume> activity) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, activity, facts.category(), facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping());
    }

    private static MerchantProfileFacts withCategory(MerchantProfileFacts facts, Fact<CategoryClassification> category) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, facts.activityVolume(), category, facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping());
    }

    private static MerchantProfileFacts factsWithMapping(MerchantProfileFacts facts, Fact<InstitutionMerchantMapping> mapping) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, facts.activityVolume(), facts.category(), facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), mapping);
    }

    private static MerchantProfile profile(MerchantProfileFacts facts, int recentCount, int baselineCount) {
        return profile(SCOPE, facts, recentCount, baselineCount);
    }

    private static MerchantProfile profile(ScopeClaim scope, MerchantProfileFacts facts, int recentCount, int baselineCount) {
        return new MerchantProfile(MerchantProfile.VERSION, MerchantProfile.COMPUTATION_VERSION, scope, OCCURRENCE,
                new Provenance(EVALUATED, TIME.minusSeconds(7200), TIME.minusSeconds(7200), TIME.plusSeconds(60), 20L, token('2')),
                available(TIME.minusSeconds(3600), TIME, recentCount),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), baselineCount), facts);
    }

    private static WindowCount available(Instant start, Instant end, int count) {
        return new WindowCount(start, end, Status.AVAILABLE, count, Reason.NONE);
    }

    private static Set<Class<?>> recordTypeGraph(Class<?> root) {
        Set<Class<?>> visited = new HashSet<>();
        collectRecordTypes(root, visited);
        return visited;
    }

    private static void collectRecordTypes(Type type, Set<Class<?>> visited) {
        if (type instanceof ParameterizedType parameterized) {
            collectRecordTypes(parameterized.getRawType(), visited);
            for (Type argument : parameterized.getActualTypeArguments()) { collectRecordTypes(argument, visited); }
        } else if (type instanceof GenericArrayType array) {
            collectRecordTypes(array.getGenericComponentType(), visited);
        } else if (type instanceof Class<?> recordType) {
            if (!visited.add(recordType) || recordType.getPackageName().startsWith("java.")) { return; }
            if (recordType.isRecord()) {
                for (var component : recordType.getRecordComponents()) {
                    collectRecordTypes(component.getGenericType(), visited);
                }
            }
        }
    }

    private static String token(char value) { return String.valueOf(value).repeat(64); }
}
