package com.frauddetection.enricher.merchant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PrivateMerchantDiagnosticReasonTest {
    @Test
    void onlyTheDocumentedOutcomeAndReasonPairsCanBeConstructed() {
        for (PrivateMerchantDiagnosticReader.Outcome outcome : PrivateMerchantDiagnosticReader.Outcome.values()) {
            for (PrivateMerchantDiagnosticReader.Reason reason : PrivateMerchantDiagnosticReader.Reason.values()) {
                boolean allowed = switch (outcome) {
                    case EVALUATED -> reason == PrivateMerchantDiagnosticReader.Reason.NONE;
                    case DENIED -> reason == PrivateMerchantDiagnosticReader.Reason.READ_NOT_AUTHORIZED;
                    case PENDING -> reason == PrivateMerchantDiagnosticReader.Reason.INITIAL_SCORED_PENDING
                            || reason == PrivateMerchantDiagnosticReader.Reason.STAGE_A_BINDING_PENDING;
                    case UNAVAILABLE -> reason == PrivateMerchantDiagnosticReader.Reason.HISTORY_UNAVAILABLE
                            || reason == PrivateMerchantDiagnosticReader.Reason.SOURCE_AUTHORITY_UNAVAILABLE
                            || reason == PrivateMerchantDiagnosticReader.Reason.DEADLINE_EXCEEDED
                            || reason == PrivateMerchantDiagnosticReader.Reason.INTERNAL_UNAVAILABLE;
                };
                PrivateMerchantDiagnosticReader.Claim claim = outcome == PrivateMerchantDiagnosticReader.Outcome.EVALUATED
                        ? PrivateMerchantDiagnosticReader.Claim.FIRST_OBSERVED_IN_COMPLETE_RECENT_WINDOW : null;
                var construction = assertThatCode(() -> new PrivateMerchantDiagnosticReader.Result(outcome, claim, reason));
                if (allowed) { construction.doesNotThrowAnyException(); }
                else { construction.isInstanceOf(IllegalArgumentException.class).hasMessage("MERCHANT_PRIVATE_RESULT_STATE"); }
            }
        }
    }

    @Test
    void reasonAndResultFormattingStayBoundedAndRedacted() {
        PrivateMerchantDiagnosticReader.Result result = new PrivateMerchantDiagnosticReader.Result(
                PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE, null,
                PrivateMerchantDiagnosticReader.Reason.SOURCE_AUTHORITY_UNAVAILABLE);

        assertThat(result.reason().name()).matches("[A-Z_]{1,48}");
        assertThat(result.toString()).isEqualTo("MerchantPrivateDiagnostic[redacted]")
                .doesNotContain("merchant", "issuer", "receipt", "signature", "transaction");
    }
}
