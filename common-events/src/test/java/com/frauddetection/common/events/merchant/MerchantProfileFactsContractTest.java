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
import com.frauddetection.common.events.merchant.MerchantProfileFacts.MerchantPopulationIdentity;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.Origin;
import com.frauddetection.common.events.merchant.MerchantProfileFacts.PopulationBasis;
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
import static org.assertj.core.api.Assertions.assertThatCode;
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
        assertThatThrownBy(() -> new MatureFraudLabels(sourcePopulationIdentity(), 25_000, 1,
                TIME.plusSeconds(60), 1)).hasMessage("MERCHANT_FACTS_LABEL_COUNT");
        assertThatThrownBy(() -> new CoveredPopulation(sourcePopulationIdentity(), 1_000_000_000_001L))
                .hasMessage("MERCHANT_FACTS_POPULATION_COUNT");
        assertThatThrownBy(() -> new MatureFraudLabels(sourcePopulationIdentity(), 1, 1,
                EVALUATED.plusMillis(1), 1)).hasMessage("MERCHANT_FACTS_LABEL_MATURITY");
    }

    @Test
    void typedFactsRequireExactSourceIdentityWindowAndPolicyVersions() {
        MerchantProfileFacts current = MerchantProfileFacts.currentPath();
        Fact<ActivityVolume> foreignSource = Fact.available(volume(2, "12.00", 5, "20.00"),
                provenance(Origin.TRANSACTION_HISTORY, token('9'), 1));
        assertThatThrownBy(() -> profile(withActivity(current, foreignSource), 2, 5))
                .hasMessage("MERCHANT_PROFILE_ACTIVITY_SOURCE_SCOPE");

        InstitutionMerchantMapping wrongEpoch = new InstitutionMerchantMapping(SCOPE.issuerRef(), SCOPE.institutionRef(),
                token('8'), SCOPE.sourceRef(), token('8'), SCOPE.merchantRef(), 2, 4, token('9'),
                TIME.minusSeconds(7200), TIME.plusSeconds(60));
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
        assertThat(original.hasCompatibleFraudPopulationFacts()).isTrue();

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
    void customerLinkageCannotExceedRecentCountWhenActivityVolumeIsMissingOrUntrusted() {
        MerchantProfileFacts current = MerchantProfileFacts.currentPath();
        Fact<CustomerLinkage> impossibleLinkage = customerLinkage(50);
        MerchantProfileFacts missingActivity = withCustomerLinkage(
                withActivity(current, Fact.missing(MerchantProfileFacts.Reason.SOURCE_MISSING)), impossibleLinkage);
        MerchantProfileFacts untrustedActivity = withCustomerLinkage(
                withActivity(current, Fact.untrusted(MerchantProfileFacts.Reason.CLIENT_ASSERTION)), impossibleLinkage);

        assertThatThrownBy(() -> profile(missingActivity, 2, 0))
                .hasMessage("MERCHANT_PROFILE_CUSTOMER_LINKAGE_COUNT");
        assertThatThrownBy(() -> profile(untrustedActivity, 2, 0))
                .hasMessage("MERCHANT_PROFILE_CUSTOMER_LINKAGE_COUNT");
    }

    @Test
    void customerLinkageRequiresAvailableRecentCountAndAcceptsValidBoundaries() {
        MerchantProfile zero = profile(withCustomerLinkage(MerchantProfileFacts.currentPath(), customerLinkage(0)), 0, 0);
        MerchantProfile full = profile(withCustomerLinkage(MerchantProfileFacts.currentPath(), customerLinkage(2)), 2, 0);
        MerchantProfile partial = profile(withCustomerLinkage(MerchantProfileFacts.currentPath(), customerLinkage(1)), 2, 0);
        assertThat(zero.distinctCustomers().value().distinctCustomers()).isZero();
        assertThat(full.distinctCustomers().value().distinctCustomers()).isEqualTo(2);
        assertThat(partial.distinctCustomers().value().distinctCustomers()).isEqualTo(1);

        MerchantProfileFacts availableLinkage = withCustomerLinkage(
                MerchantProfileFacts.currentPath(), customerLinkage(0));
        assertThatThrownBy(() -> profileWithUnavailableWindows(availableLinkage))
                .hasMessage("MERCHANT_PROFILE_CUSTOMER_LINKAGE_COUNT");

        MerchantProfileFacts untrustedLinkage = MerchantProfileFacts.currentPath();
        MerchantProfile untrusted = profile(untrustedLinkage, 2, 0);
        assertThat(untrusted.distinctCustomers().status()).isEqualTo(MerchantProfileFacts.Status.UNTRUSTED);
        assertThat(untrusted.distinctCustomers().value()).isNull();
        MerchantProfileFacts missingLinkage = withCustomerLinkage(untrustedLinkage,
                Fact.missing(MerchantProfileFacts.Reason.SOURCE_MISSING));
        MerchantProfile missing = profile(missingLinkage, 2, 0);
        assertThat(missing.distinctCustomers().status()).isEqualTo(MerchantProfileFacts.Status.MISSING);
        assertThat(missing.distinctCustomers().value()).isNull();
    }

    @Test
    void activityVolumeCannotBeKnownBeforeItsFullWindowEnds() {
        MerchantProfileFacts facts = withActivity(availableFacts(), Fact.available(
                volume(2, "12.00", 5, "20.00"),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1, TIME.minusMillis(1))));

        assertThatThrownBy(() -> profile(facts, 2, 5))
                .hasMessage("MERCHANT_PROFILE_ACTIVITY_KNOWLEDGE_TIME");
    }

    @Test
    void customerLinkageCannotBeKnownBeforeItsWindowEnds() {
        MerchantProfileFacts facts = withCustomerLinkage(availableFacts(), Fact.available(
                new CustomerLinkage(TIME.minusSeconds(3600), TIME, 1, 2),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 2, TIME.minusMillis(1))));

        assertThatThrownBy(() -> profile(facts, 2, 5))
                .hasMessage("MERCHANT_PROFILE_CUSTOMER_LINKAGE_KNOWLEDGE_TIME");
    }

    @Test
    void aggregateKnowledgeWindowsAllowInclusiveEndAndLeaveUnavailableFactsUntimed() {
        MerchantProfileFacts current = availableFacts();
        for (Instant observedAt : new Instant[] {TIME, TIME.plusMillis(1)}) {
            MerchantProfileFacts activityAtBoundary = withActivity(current, Fact.available(
                    volume(2, "12.00", 5, "20.00"),
                    provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1, observedAt)));
            assertThatCode(() -> profile(activityAtBoundary, 2, 5)).doesNotThrowAnyException();

            MerchantProfileFacts linkageAtBoundary = withCustomerLinkage(current, Fact.available(
                    new CustomerLinkage(TIME.minusSeconds(3600), TIME, 1, 2),
                    provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 2, observedAt)));
            assertThatCode(() -> profile(linkageAtBoundary, 2, 5)).doesNotThrowAnyException();
        }

        MerchantProfileFacts futureLinkage = withCustomerLinkage(current, Fact.available(
                new CustomerLinkage(TIME.minusSeconds(3600), TIME, 1, 2),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 2, EVALUATED.plusMillis(1))));
        assertThatThrownBy(() -> profile(futureLinkage, 2, 5))
                .hasMessage("MERCHANT_PROFILE_FACT_PROVENANCE_TIME");

        assertThatCode(() -> profile(MerchantProfileFacts.currentPath(), 2, 0)).doesNotThrowAnyException();
        MerchantProfileFacts missing = withCustomerLinkage(
                withActivity(MerchantProfileFacts.currentPath(), Fact.missing(MerchantProfileFacts.Reason.SOURCE_MISSING)),
                Fact.missing(MerchantProfileFacts.Reason.SOURCE_MISSING));
        MerchantProfile unavailable = profile(missing, 2, 0);
        assertThat(unavailable.volume().value()).isNull();
        assertThat(unavailable.distinctCustomers().value()).isNull();
    }

    @Test
    void matureLabelsCannotClaimAWindowKnownAfterTheirEvidenceWasObserved() {
        MerchantProfileFacts current = availableFacts();
        MerchantProfileFacts contradictory = withFraudFacts(current,
                Fact.available(new MatureFraudLabels(sourcePopulationIdentity(), 1, 1, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5, TIME.plusSeconds(30))),
                current.coveredPopulation(), current.institutionMerchantMapping());

        assertThatThrownBy(() -> profile(contradictory, 2, 5))
                .hasMessage("MERCHANT_PROFILE_LABEL_KNOWLEDGE_TIME");
    }

    @Test
    void populationKnowledgeTimelineAllowsEqualityAndRejectsOneMillisecondFutureClaims() {
        MerchantProfileFacts current = availableFacts();
        MerchantPopulationIdentity localAtWindowEnd = sourcePopulationIdentity(TIME);
        MerchantProfileFacts localBoundary = withFraudFacts(current,
                Fact.available(new MatureFraudLabels(localAtWindowEnd, 1, 1, TIME, 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5, TIME)),
                Fact.available(new CoveredPopulation(localAtWindowEnd, 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4, TIME)),
                current.institutionMerchantMapping());
        assertThat(profile(localBoundary, 2, 5).hasCompatibleFraudPopulationFacts()).isTrue();

        MerchantPopulationIdentity mappedAtWindowEnd = mappedPopulationIdentity(4, TIME);
        MerchantProfileFacts mappedBoundary = withFraudFacts(current,
                Fact.available(new MatureFraudLabels(mappedAtWindowEnd, 1, 3, TIME, 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5, TIME)),
                Fact.available(new CoveredPopulation(mappedAtWindowEnd, 20),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4, TIME)),
                Fact.available(mapping(), provenance(Origin.INSTITUTION_IDENTITY_MAPPING, token('7'), 4, TIME)));
        assertThat(profile(mappedBoundary, 2, 5).hasCompatibleFraudPopulationFacts()).isTrue();

        MerchantPopulationIdentity oneMillisecondLater = sourcePopulationIdentity(TIME.plusMillis(1));
        MerchantProfileFacts prematureLabels = withFraudFacts(current,
                Fact.available(new MatureFraudLabels(oneMillisecondLater, 1, 1, TIME.plusMillis(1), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5, TIME)),
                Fact.available(new CoveredPopulation(oneMillisecondLater, 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4, TIME.plusMillis(1))),
                current.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(prematureLabels, 2, 5))
                .hasMessage("MERCHANT_PROFILE_LABEL_KNOWLEDGE_TIME");

        MerchantProfileFacts prematurePopulation = withFraudFacts(current,
                Fact.available(new MatureFraudLabels(oneMillisecondLater, 1, 1, TIME, 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5, TIME.plusMillis(1))),
                Fact.available(new CoveredPopulation(oneMillisecondLater, 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4, TIME)),
                current.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(prematurePopulation, 2, 5))
                .hasMessage("MERCHANT_PROFILE_POPULATION_KNOWLEDGE_TIME");

        MerchantPopulationIdentity knownAtEvaluation = sourcePopulationIdentity(EVALUATED);
        MerchantProfileFacts futureAggregate = withFraudFacts(current,
                Fact.available(new MatureFraudLabels(knownAtEvaluation, 1, 1, EVALUATED, 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5, EVALUATED.plusMillis(1))),
                Fact.available(new CoveredPopulation(knownAtEvaluation, 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4, EVALUATED.plusMillis(1))),
                current.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(futureAggregate, 2, 5))
                .hasMessage("MERCHANT_PROFILE_FACT_PROVENANCE_TIME");

        assertThatThrownBy(() -> new MatureFraudLabels(localAtWindowEnd, 1, 1, TIME.minusMillis(1), 5))
                .hasMessage("MERCHANT_FACTS_LABEL_MATURITY");
        assertThatThrownBy(() -> sourcePopulationIdentity(TIME.minusMillis(1)))
                .hasMessage("MERCHANT_FACTS_POPULATION_WINDOW");
    }

    @Test
    void localPopulationCannotClaimMoreLabelsOrEligibleRowsThanItsObservationCohort() {
        MerchantProfileFacts valid = availableFacts();
        assertThat(profile(valid, 2, 5).hasCompatibleFraudPopulationFacts()).isTrue();

        MerchantPopulationIdentity local = sourcePopulationIdentity();
        MerchantProfileFacts counterexample = withFraudFacts(valid,
                Fact.available(new MatureFraudLabels(local, 1, 3, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(local, 20),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4)),
                valid.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(counterexample, 2, 5))
                .hasMessage("MERCHANT_PROFILE_LABEL_OBSERVATION_COHORT");
    }

    @Test
    void fraudLabelsAndPopulationMustShareExactIdentityAndVersions() {
        MerchantProfileFacts valid = availableFacts();
        MerchantPopulationIdentity foreignMerchant = new MerchantPopulationIdentity(PopulationBasis.SOURCE_OBSERVATION_COHORT,
                SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), token('9'),
                SCOPE.identityVersion(), null, 0, null, TIME.minusSeconds(3600), TIME, TIME.plusSeconds(60), 4, token('2'));
        MerchantProfileFacts foreign = withFraudFacts(valid,
                Fact.available(new MatureFraudLabels(foreignMerchant, 1, 1, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(foreignMerchant, 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4)),
                valid.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(foreign, 2, 5)).hasMessage("MERCHANT_PROFILE_POPULATION_IDENTITY_SCOPE");

        MerchantPopulationIdentity rotatedIdentity = new MerchantPopulationIdentity(PopulationBasis.SOURCE_OBSERVATION_COHORT,
                SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(),
                SCOPE.identityVersion() + 1, null, 0, null, TIME.minusSeconds(3600), TIME, EVALUATED, 4, token('2'));
        MerchantProfileFacts rotated = withFraudFacts(valid,
                Fact.available(new MatureFraudLabels(rotatedIdentity, 1, 1, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(rotatedIdentity, 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4)),
                valid.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(rotated, 2, 5)).hasMessage("MERCHANT_PROFILE_POPULATION_IDENTITY_SCOPE");

        MerchantPopulationIdentity wrongDefinition = new MerchantPopulationIdentity(PopulationBasis.SOURCE_OBSERVATION_COHORT,
                SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(),
                SCOPE.identityVersion(), null, 0, null, TIME.minusSeconds(3600), TIME, TIME.plusSeconds(60), 5, token('2'));
        MerchantProfileFacts mismatchedVersion = withFraudFacts(valid,
                Fact.available(new MatureFraudLabels(sourcePopulationIdentity(), 1, 1, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(wrongDefinition, 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 5)),
                valid.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(mismatchedVersion, 2, 5))
                .hasMessage("MERCHANT_PROFILE_POPULATION_IDENTITY_MISMATCH");
    }

    @Test
    void broaderPopulationRequiresAnExplicitStructurallyMatchingMappingClaim() {
        MerchantProfileFacts current = MerchantProfileFacts.currentPath();
        MerchantPopulationIdentity mapped = mappedPopulationIdentity(4);
        Fact<InstitutionMerchantMapping> mapping = Fact.available(mapping(),
                provenance(Origin.INSTITUTION_IDENTITY_MAPPING, token('7'), 4));
        MerchantProfileFacts mappedFacts = withFraudFacts(availableFacts(),
                Fact.available(new MatureFraudLabels(mapped, 1, 3, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(mapped, 20),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4)), mapping);

        // AVAILABLE mapping provenance remains an adapter claim; it is not production admission.
        MerchantProfile structurallyBound = profile(mappedFacts, 2, 5);
        assertThat(structurallyBound.hasCompatibleFraudPopulationFacts()).isTrue();

        MerchantProfileFacts overCountedLabels = withFraudFacts(mappedFacts,
                Fact.available(new MatureFraudLabels(mapped, 11, 10, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                mappedFacts.coveredPopulation(), mapping);
        assertThatThrownBy(() -> profile(overCountedLabels, 2, 5))
                .hasMessage("MERCHANT_PROFILE_LABEL_POPULATION");

        MerchantProfileFacts noMapping = withFraudFacts(mappedFacts, mappedFacts.matureFraudLabels(),
                mappedFacts.coveredPopulation(), current.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(noMapping, 2, 5))
                .hasMessage("MERCHANT_PROFILE_POPULATION_MAPPING_REQUIRED");

        MerchantProfileFacts untrustedMapping = withFraudFacts(mappedFacts, mappedFacts.matureFraudLabels(),
                mappedFacts.coveredPopulation(), Fact.untrusted(MerchantProfileFacts.Reason.SOURCE_NOT_ADMITTED));
        assertThatThrownBy(() -> profile(untrustedMapping, 2, 5))
                .hasMessage("MERCHANT_PROFILE_POPULATION_MAPPING_REQUIRED");

        MerchantProfileFacts wrongMapping = withFraudFacts(mappedFacts, mappedFacts.matureFraudLabels(),
                mappedFacts.coveredPopulation(), Fact.available(new InstitutionMerchantMapping(SCOPE.issuerRef(),
                        token('9'), token('8'), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(),
                        SCOPE.identityVersion(), 4, token('9'), TIME.minusSeconds(7200), TIME.plusSeconds(60)),
                        provenance(Origin.INSTITUTION_IDENTITY_MAPPING, token('7'), 4)));
        assertThatThrownBy(() -> profile(wrongMapping, 2, 5))
                .hasMessage("MERCHANT_PROFILE_IDENTITY_MAPPING_SCOPE");

        InstitutionMerchantMapping mappingForOccurrenceOnly = new InstitutionMerchantMapping(SCOPE.issuerRef(),
                SCOPE.institutionRef(), token('8'), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(),
                SCOPE.identityVersion(), 4, token('9'), TIME.minusSeconds(60), TIME.plusSeconds(60));
        MerchantProfileFacts partialWindowMapping = withFraudFacts(mappedFacts, mappedFacts.matureFraudLabels(),
                mappedFacts.coveredPopulation(), Fact.available(mappingForOccurrenceOnly,
                        provenance(Origin.INSTITUTION_IDENTITY_MAPPING, token('7'), 4)));
        assertThatThrownBy(() -> profile(partialWindowMapping, 2, 5))
                .hasMessage("MERCHANT_PROFILE_IDENTITY_MAPPING_SCOPE");
    }

    @Test
    void populationWindowRetentionMaturityAndZeroDenominatorAreFailClosed() {
        MerchantProfileFacts valid = availableFacts();
        MerchantPopulationIdentity wrongWindow = new MerchantPopulationIdentity(PopulationBasis.SOURCE_OBSERVATION_COHORT,
                SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(),
                SCOPE.identityVersion(), null, 0, null, TIME.minusSeconds(7200), TIME.minusSeconds(3600), EVALUATED, 4,
                token('2'));
        assertThatThrownBy(() -> profile(withFraudFacts(valid,
                Fact.available(new MatureFraudLabels(wrongWindow, 1, 1, TIME, 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                valid.coveredPopulation(), valid.institutionMerchantMapping()), 2, 5))
                .hasMessage("MERCHANT_PROFILE_POPULATION_IDENTITY_SCOPE");

        assertThatThrownBy(() -> new MatureFraudLabels(sourcePopulationIdentity(), 1, 1, EVALUATED.plusMillis(1), 5))
                .hasMessage("MERCHANT_FACTS_LABEL_MATURITY");

        MerchantProfileFacts duplicatesOverCohort = withFraudFacts(valid,
                Fact.available(new MatureFraudLabels(sourcePopulationIdentity(), 2, 1, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                valid.coveredPopulation(), valid.institutionMerchantMapping());
        assertThatThrownBy(() -> profile(duplicatesOverCohort, 2, 5))
                .hasMessage("MERCHANT_PROFILE_LABEL_OBSERVATION_COHORT");

        MerchantProfileFacts zeroDenominator = withFraudFacts(valid,
                Fact.available(new MatureFraudLabels(sourcePopulationIdentity(), 0, 0, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(sourcePopulationIdentity(), 0),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4)), valid.institutionMerchantMapping());
        assertThat(profile(zeroDenominator, 2, 5).hasCompatibleFraudPopulationFacts()).isFalse();

        MerchantProfile.Provenance changedRetention = new MerchantProfile.Provenance(EVALUATED,
                TIME.minusSeconds(7200), TIME.minusSeconds(7200), TIME.plusSeconds(60), 20L, token('9'));
        assertThatThrownBy(() -> profile(valid, 2, 5, changedRetention))
                .hasMessage("MERCHANT_PROFILE_POPULATION_OBSERVATION_COMMITMENT");
    }

    @Test
    void labelsWithoutDenominatorAreNotACompatibleFraudPopulation() {
        MerchantProfileFacts valid = availableFacts();
        MerchantProfileFacts noDenominator = withFraudFacts(valid, valid.matureFraudLabels(),
                Fact.missing(MerchantProfileFacts.Reason.POPULATION_COVERAGE_MISSING), valid.institutionMerchantMapping());
        MerchantProfile profile = profile(noDenominator, 2, 5);
        assertThat(profile.labels().status()).isEqualTo(MerchantProfileFacts.Status.AVAILABLE);
        assertThat(profile.populationDenominator().status()).isEqualTo(MerchantProfileFacts.Status.MISSING);
        assertThat(profile.hasCompatibleFraudPopulationFacts()).isFalse();

        MerchantProfileFacts untrustedDenominator = withFraudFacts(valid, valid.matureFraudLabels(),
                Fact.untrusted(MerchantProfileFacts.Reason.SOURCE_NOT_ADMITTED), valid.institutionMerchantMapping());
        assertThat(profile(untrustedDenominator, 2, 5).hasCompatibleFraudPopulationFacts()).isFalse();
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
        assertThat(facts.toString()).doesNotContain(token('2'), token('7'), token('8'), SCOPE.issuerRef());
        assertThat(sourcePopulationIdentity().toString()).doesNotContain(token('2'), SCOPE.merchantRef());

        MerchantProfile profile = profile(facts, 2, 5);
        String diagnostics = profile.diagnostics().toString();
        assertThat(diagnostics).doesNotContain(token('6'), token('7'), token('8'));
        assertThat(profile.toString()).doesNotContain("5411", "GROCERY", token('7'), token('8'));
        assertThat(profile.canonicalBytes().length).isLessThanOrEqualTo(MerchantProfile.MAX_CANONICAL_BYTES);
        assertThatThrownBy(() -> new MerchantProfileFacts(1, facts.activityVolume(), facts.category(), facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping()))
                .hasMessage("MERCHANT_FACTS_VERSION");
        assertThatThrownBy(() -> new MerchantProfileFacts.Provenance(Origin.MCC_TAXONOMY, "raw-taxonomy-id", 1, 1, TIME, token('4')))
                .hasMessage("MERCHANT_FACTS_PROVENANCE_SOURCE");
    }

    @Test
    void nestedActivityWindowRedactsMoneyFromStringsWithoutChangingCanonicalEvidence() {
        ActivityWindow recent = new ActivityWindow(TIME.minusSeconds(3600), TIME, 2,
                new Money(new BigDecimal("9876.54"), "EUR"));
        ActivityWindow baseline = new ActivityWindow(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 5,
                new Money(new BigDecimal("345.67"), "EUR"));
        ActivityVolume activity = new ActivityVolume(recent, baseline);
        MerchantProfileFacts base = availableFacts();
        Fact<ActivityVolume> activityFact = Fact.available(activity,
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 1));
        MerchantProfileFacts facts = new MerchantProfileFacts(MerchantProfileFacts.VERSION, activityFact, base.category(),
                base.customerLinkage(), base.matureFraudLabels(), base.coveredPopulation(), base.institutionMerchantMapping());
        MerchantProfile profile = profile(facts, 2, 5);

        for (Object printable : new Object[] {recent, baseline, activity, activityFact, facts, profile,
                profile.diagnostics()}) {
            assertThat(printable.toString()).doesNotContain("9876.54", "345.67", "EUR", token('a'), token('e'));
        }
        String canonical = new String(facts.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(canonical).contains("9876.54", "345.67", "EUR");
        assertThatThrownBy(() -> new ActivityWindow(TIME.minusSeconds(60), TIME, 1,
                new Money(new BigDecimal("9876.54"), "SECRET_CURRENCY")))
                .hasMessage("MERCHANT_FACTS_CURRENCY").hasNoCause();
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
                Fact.available(new MatureFraudLabels(sourcePopulationIdentity(), 1, 1, TIME.plusSeconds(60), 5),
                        provenance(Origin.ADJUDICATED_FEEDBACK, token('5'), 5)),
                Fact.available(new CoveredPopulation(sourcePopulationIdentity(), 2),
                        provenance(Origin.POPULATION_COVERAGE, token('4'), 4)),
                Fact.missing(MerchantProfileFacts.Reason.INSTITUTION_IDENTITY_MAPPING_REQUIRED));
    }

    private static MerchantPopulationIdentity sourcePopulationIdentity() {
        return sourcePopulationIdentity(TIME.plusSeconds(60));
    }

    private static MerchantPopulationIdentity sourcePopulationIdentity(Instant asOf) {
        return new MerchantPopulationIdentity(PopulationBasis.SOURCE_OBSERVATION_COHORT, SCOPE.issuerRef(),
                SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(),
                SCOPE.identityVersion(), null, 0, null, TIME.minusSeconds(3600), TIME, asOf, 4, token('2'));
    }

    private static MerchantPopulationIdentity mappedPopulationIdentity(int mappingVersion) {
        return mappedPopulationIdentity(mappingVersion, TIME.plusSeconds(60));
    }

    private static MerchantPopulationIdentity mappedPopulationIdentity(int mappingVersion, Instant asOf) {
        return new MerchantPopulationIdentity(PopulationBasis.INSTITUTION_MAPPED_COHORT, SCOPE.issuerRef(),
                SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(),
                SCOPE.identityVersion(), token('8'), mappingVersion, token('9'), TIME.minusSeconds(3600), TIME,
                asOf, 4, token('a'));
    }

    private static InstitutionMerchantMapping mapping() {
        return new InstitutionMerchantMapping(SCOPE.issuerRef(), SCOPE.institutionRef(), token('8'),
                SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(), SCOPE.identityVersion(), 4,
                token('9'), TIME.minusSeconds(7200), TIME.plusSeconds(60));
    }

    private static ActivityVolume volume(int recentCount, String recentAmount, int baselineCount, String baselineAmount) {
        return new ActivityVolume(new ActivityWindow(TIME.minusSeconds(3600), TIME, recentCount,
                new Money(new BigDecimal(recentAmount), "PLN")),
                new ActivityWindow(TIME.minusSeconds(7200), TIME.minusSeconds(3600), baselineCount,
                        new Money(new BigDecimal(baselineAmount), "PLN")));
    }

    private static MerchantProfileFacts.Provenance provenance(Origin origin, String sourceRef, int policyVersion) {
        return provenance(origin, sourceRef, policyVersion, TIME.plusSeconds(60));
    }

    private static MerchantProfileFacts.Provenance provenance(Origin origin, String sourceRef, int policyVersion, Instant observedAt) {
        return new MerchantProfileFacts.Provenance(origin, sourceRef, 1, policyVersion, observedAt, token('3'));
    }

    private static MerchantProfileFacts withActivity(MerchantProfileFacts facts, Fact<ActivityVolume> activity) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, activity, facts.category(), facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping());
    }

    private static MerchantProfileFacts withCustomerLinkage(MerchantProfileFacts facts, Fact<CustomerLinkage> linkage) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, facts.activityVolume(), facts.category(), linkage,
                facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping());
    }

    private static Fact<CustomerLinkage> customerLinkage(int count) {
        return Fact.available(new CustomerLinkage(TIME.minusSeconds(3600), TIME, count, 2),
                provenance(Origin.TRANSACTION_HISTORY, SCOPE.sourceRef(), 2));
    }

    private static MerchantProfile profileWithUnavailableWindows(MerchantProfileFacts facts) {
        return new MerchantProfile(MerchantProfile.VERSION, MerchantProfile.COMPUTATION_VERSION, SCOPE, OCCURRENCE,
                provenance(), new WindowCount(TIME.minusSeconds(3600), TIME, Status.UNAVAILABLE, null, Reason.MISSING_COVERAGE),
                new WindowCount(TIME.minusSeconds(7200), TIME.minusSeconds(3600), Status.UNAVAILABLE, null, Reason.MISSING_COVERAGE),
                facts);
    }

    private static MerchantProfileFacts withCategory(MerchantProfileFacts facts, Fact<CategoryClassification> category) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, facts.activityVolume(), category, facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), facts.institutionMerchantMapping());
    }

    private static MerchantProfileFacts factsWithMapping(MerchantProfileFacts facts, Fact<InstitutionMerchantMapping> mapping) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, facts.activityVolume(), facts.category(), facts.customerLinkage(),
                facts.matureFraudLabels(), facts.coveredPopulation(), mapping);
    }

    private static MerchantProfileFacts withFraudFacts(MerchantProfileFacts facts,
                                                        Fact<MatureFraudLabels> labels,
                                                        Fact<CoveredPopulation> population,
                                                        Fact<InstitutionMerchantMapping> mapping) {
        return new MerchantProfileFacts(MerchantProfileFacts.VERSION, facts.activityVolume(), facts.category(),
                facts.customerLinkage(), labels, population, mapping);
    }

    private static MerchantProfile profile(MerchantProfileFacts facts, int recentCount, int baselineCount) {
        return profile(SCOPE, facts, recentCount, baselineCount);
    }

    private static MerchantProfile profile(ScopeClaim scope, MerchantProfileFacts facts, int recentCount, int baselineCount) {
        return profile(scope, facts, recentCount, baselineCount, provenance());
    }

    private static MerchantProfile profile(MerchantProfileFacts facts, int recentCount, int baselineCount,
                                           MerchantProfile.Provenance provenance) {
        return profile(SCOPE, facts, recentCount, baselineCount, provenance);
    }

    private static MerchantProfile profile(ScopeClaim scope, MerchantProfileFacts facts, int recentCount, int baselineCount,
                                           MerchantProfile.Provenance provenance) {
        return new MerchantProfile(MerchantProfile.VERSION, MerchantProfile.COMPUTATION_VERSION, scope, OCCURRENCE,
                provenance,
                available(TIME.minusSeconds(3600), TIME, recentCount),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), baselineCount), facts);
    }

    private static MerchantProfile.Provenance provenance() {
        return new Provenance(EVALUATED, TIME.minusSeconds(7200), TIME.minusSeconds(7200), TIME.plusSeconds(60),
                20L, token('2'));
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
