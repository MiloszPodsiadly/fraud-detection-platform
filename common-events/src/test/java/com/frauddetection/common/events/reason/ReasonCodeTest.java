package com.frauddetection.common.events.reason;

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
                "RAPID_TRANSFER_FRAUD_CASE",
                "ML_MODEL_UNAVAILABLE",
                "UNKNOWN"
        );
    }

    @Test
    void definitionsDoNotClaimVerdictsEvidenceOrFinalRisk() {
        assertThat(ReasonCode.values()).allSatisfy(reasonCode -> {
            String combined = (reasonCode.name() + " " + reasonCode.title() + " " + reasonCode.description())
                    .toLowerCase(Locale.ROOT);
            assertThat(combined).doesNotContain("confirmed fraud");
            assertThat(combined).doesNotContain("fraud proof");
            assertThat(combined).doesNotContain("evidence exists");
            assertThat(combined).doesNotContain("final outcome");
            assertThat(combined).doesNotContain("final risk");
        });
    }

    @Test
    void parsesCanonicalAndLegacyValuesWithoutThrowing() {
        assertThat(ReasonCode.parseLegacy("HIGH_AMOUNT").reasonCode()).isEqualTo(ReasonCode.HIGH_TRANSACTION_AMOUNT);
        assertThat(ReasonCode.parseLegacy("high_amount").reasonCode()).isEqualTo(ReasonCode.HIGH_TRANSACTION_AMOUNT);
        assertThat(ReasonCode.parseLegacy(" High_Amount ").reasonCode()).isEqualTo(ReasonCode.HIGH_TRANSACTION_AMOUNT);
        assertThat(ReasonCode.parseLegacy("countryMismatch").reasonCode()).isEqualTo(ReasonCode.COUNTRY_MISMATCH);
        assertThat(ReasonCode.parseLegacy("RAPID_PLN_20K_BURST").reasonCode()).isEqualTo(ReasonCode.RAPID_PLN_20K_BURST);
        assertThat(ReasonCode.parseLegacy("rapidTransferFraudCaseCandidate").reasonCode())
                .isEqualTo(ReasonCode.RAPID_TRANSFER_FRAUD_CASE);
    }

    @Test
    void handlesNullBlankAndFutureValuesExplicitly() {
        assertThat(ReasonCode.parseLegacy(null)).isEqualTo(new ReasonCodeParseResult(
                ReasonCode.UNKNOWN,
                ReasonCodeParseStatus.NULL_ITEM,
                null
        ));
        assertThat(ReasonCode.parseLegacy("   ").status()).isEqualTo(ReasonCodeParseStatus.BLANK);
        assertThat(ReasonCode.parseLegacy("some-new-future-code")).isEqualTo(new ReasonCodeParseResult(
                ReasonCode.UNKNOWN,
                ReasonCodeParseStatus.UNSUPPORTED,
                "some-new-future-code"
        ));
        assertThat(ReasonCode.parseLegacy("FRAUD_CONFIRMED").reasonCode()).isEqualTo(ReasonCode.UNKNOWN);
        assertThat(ReasonCode.parseLegacy("AML_ESCALATION_REQUIRED").reasonCode()).isEqualTo(ReasonCode.UNKNOWN);
        assertThat(ReasonCode.known("UNKNOWN")).isEmpty();
    }

    @Test
    void nullListMeansNoReasonCodeDataRatherThanConcreteUnknownSignal() {
        assertThat(ReasonCode.parseLegacyList(null)).isEmpty();
        assertThat(ReasonCode.parseLegacyList(List.of())).isEmpty();
    }

    @Test
    void preservesUnsupportedRawValueInParseResultAndEmitsCompatibilityWireValues() {
        List<ReasonCodeParseResult> parsed = ReasonCode.parseLegacyList(Arrays.asList(
                "COUNTRY_MISMATCH",
                "some-new-future-code",
                null,
                "countryMismatch"
        ));

        assertThat(parsed).extracting(ReasonCodeParseResult::rawValue)
                .contains("some-new-future-code");
        assertThat(ReasonCode.wireValues(parsed))
                .containsExactly("COUNTRY_MISMATCH", "UNKNOWN");
    }
}
