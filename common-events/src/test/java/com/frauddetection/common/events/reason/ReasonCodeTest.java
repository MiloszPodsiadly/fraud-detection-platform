package com.frauddetection.common.events.reason;

import com.frauddetection.common.events.features.FraudFeatureContract;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ReasonCodeTest {

    @Test
    void everyReasonCodeHasCategoryAndDefinition() {
        assertThat(ReasonCode.values()).allSatisfy(reasonCode -> {
            assertThat(reasonCode.wireValue()).isNotBlank();
            assertThat(reasonCode.category()).isNotNull();
            assertThat(reasonCode.title()).isNotBlank();
            assertThat(reasonCode.description()).isNotBlank();
        });
    }

    @Test
    void wireValuesAreUniqueAndStable() {
        List<String> wireValues = Arrays.stream(ReasonCode.values())
                .map(ReasonCode::wireValue)
                .toList();

        assertThat(wireValues).doesNotHaveDuplicates();
        assertThat(wireValues).contains(
                "COUNTRY_MISMATCH",
                "HIGH_TRANSACTION_AMOUNT",
                "RAPID_PLN_20K_BURST",
                "ML_MODEL_UNAVAILABLE",
                "UNKNOWN"
        );
    }

    @Test
    void definitionsDoNotClaimVerdictsEvidenceCaseCreationOrFinalRisk() {
        assertThat(ReasonCode.values()).allSatisfy(reasonCode -> {
            String combined = (reasonCode.title() + " " + reasonCode.description())
                    .toLowerCase(Locale.ROOT);
            assertThat(combined).doesNotContain("confirmed fraud");
            assertThat(combined).doesNotContain("fraud proof");
            assertThat(combined).doesNotContain("evidence exists");
            assertThat(combined).doesNotContain("final outcome");
            assertThat(combined).doesNotContain("final risk");
            assertThat(combined).doesNotContain("case created");
            assertThat(combined).doesNotContain("fraud case exists");
            assertThat(combined).doesNotContain("verdict");
            assertThat(combined).doesNotContain("analyst confirmed");
        });
    }

    @Test
    void parsesCanonicalAndSupportedAliasValuesWithoutThrowing() {
        assertThat(ReasonCode.parseInput("HIGH_AMOUNT").reasonCode()).isEqualTo(ReasonCode.HIGH_TRANSACTION_AMOUNT);
        assertThat(ReasonCode.parseInput("high_amount").reasonCode()).isEqualTo(ReasonCode.HIGH_TRANSACTION_AMOUNT);
        assertThat(ReasonCode.parseInput(" High_Amount ").reasonCode()).isEqualTo(ReasonCode.HIGH_TRANSACTION_AMOUNT);
        assertThat(ReasonCode.parseInput("COUNTRY_MISMATCH").reasonCode()).isEqualTo(ReasonCode.COUNTRY_MISMATCH);
        assertThat(ReasonCode.parseInput("RAPID_PLN_20K_BURST").reasonCode()).isEqualTo(ReasonCode.RAPID_PLN_20K_BURST);
    }

    @Test
    void parsesCurrentMlFeatureContributionKeysAsCanonicalReasonCodes() {
        assertThat(ReasonCode.parseInput(FraudFeatureContract.DEVICE_NOVELTY).reasonCode())
                .isEqualTo(ReasonCode.DEVICE_NOVELTY);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.COUNTRY_MISMATCH).reasonCode())
                .isEqualTo(ReasonCode.COUNTRY_MISMATCH);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.PROXY_OR_VPN_DETECTED).reasonCode())
                .isEqualTo(ReasonCode.PROXY_OR_VPN);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.MERCHANT_FREQUENCY_7D).reasonCode())
                .isEqualTo(ReasonCode.MERCHANT_CONCENTRATION);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.RECENT_TRANSACTION_COUNT).reasonCode())
                .isEqualTo(ReasonCode.RECENT_TRANSACTION_SPIKE);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE).reasonCode())
                .isEqualTo(ReasonCode.TRANSACTION_VELOCITY);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.RECENT_AMOUNT_SUM).reasonCode())
                .isEqualTo(ReasonCode.RECENT_AMOUNT_ACCUMULATION);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN).reasonCode())
                .isEqualTo(ReasonCode.RECENT_AMOUNT_ACCUMULATION);
        assertThat(ReasonCode.parseInput(FraudFeatureContract.RAPID_TRANSFER_BURST).reasonCode())
                .isEqualTo(ReasonCode.RAPID_PLN_20K_BURST);
    }

    @Test
    void handlesNullBlankAndFutureValuesExplicitly() {
        assertThat(ReasonCode.parseInput(null)).isEqualTo(new ReasonCodeParseResult(
                ReasonCode.UNKNOWN,
                ReasonCodeParseStatus.NULL_ITEM,
                null
        ));
        assertThat(ReasonCode.parseInput("   ").status()).isEqualTo(ReasonCodeParseStatus.BLANK);
        assertThat(ReasonCode.parseInput("some-new-future-code")).isEqualTo(new ReasonCodeParseResult(
                ReasonCode.UNKNOWN,
                ReasonCodeParseStatus.UNSUPPORTED,
                "some-new-future-code"
        ));
        assertThat(ReasonCode.parseInput("FRAUD_CONFIRMED").reasonCode()).isEqualTo(ReasonCode.UNKNOWN);
        assertThat(ReasonCode.parseInput("AML_ESCALATION_REQUIRED").reasonCode()).isEqualTo(ReasonCode.UNKNOWN);
        assertThat(ReasonCode.parseInput("UNSUPPORTED_FUTURE_CASE_SIGNAL")).isEqualTo(new ReasonCodeParseResult(
                ReasonCode.UNKNOWN,
                ReasonCodeParseStatus.UNSUPPORTED,
                "UNSUPPORTED_FUTURE_CASE_SIGNAL"
        ));
        assertThat(ReasonCode.known("UNKNOWN")).isEmpty();
    }

    @Test
    void unknownCompatibilityMarkerIsNotKnownScoringSignal() {
        List<ReasonCodeParseResult> parsed = ReasonCode.parseInputList(List.of("UNKNOWN"));

        assertThat(ReasonCode.known("UNKNOWN")).isEmpty();
        assertThat(ReasonCode.parseInput("UNKNOWN").reasonCode()).isEqualTo(ReasonCode.UNKNOWN);
        assertThat(ReasonCode.supportedWireValues(parsed)).isEmpty();
    }

    @Test
    void nullListMeansNoReasonCodeDataRatherThanConcreteUnknownSignal() {
        assertThat(ReasonCode.parseInputList(null)).isEmpty();
        assertThat(ReasonCode.parseInputList(List.of())).isEmpty();
    }

    @Test
    void preservesUnsupportedRawValueInParseResultAndEmitsCompatibilityWireValues() {
        List<ReasonCodeParseResult> parsed = ReasonCode.parseInputList(Arrays.asList(
                "COUNTRY_MISMATCH",
                "some-new-future-code",
                null,
                "COUNTRY_MISMATCH"
        ));

        assertThat(parsed).extracting(ReasonCodeParseResult::rawValue)
                .contains("some-new-future-code");
        assertThat(ReasonCode.wireValues(parsed))
                .containsExactly("COUNTRY_MISMATCH", "UNKNOWN");
    }

    @Test
    void supportedWireValuesOnlyEmitKnownScoringSignals() {
        List<ReasonCodeParseResult> parsed = ReasonCode.parseInputList(Arrays.asList(
                "COUNTRY_MISMATCH",
                "some-new-future-code",
                " ",
                null,
                "FRAUD_CONFIRMED",
                "AML_ESCALATION_REQUIRED",
                "UNKNOWN"
        ));

        assertThat(ReasonCode.supportedWireValues(parsed))
                .containsExactly("COUNTRY_MISMATCH");
    }

}
