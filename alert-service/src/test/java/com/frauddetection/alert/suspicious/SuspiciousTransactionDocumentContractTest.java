package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuspiciousTransactionDocumentContractTest {

    @Test
    void nullReasonCodesNormalizeToEmptyList() {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();

        document.setReasonCodes(null);

        assertThat(document.getReasonCodes()).isEmpty();
    }

    @Test
    void reasonCodesAreDefensivelyCopied() {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();
        List<String> reasonCodes = new ArrayList<>(List.of("HIGH_AMOUNT"));

        document.setReasonCodes(reasonCodes);
        reasonCodes.add("DEVICE_NOVELTY");

        assertThat(document.getReasonCodes()).containsExactly("HIGH_AMOUNT");
        assertThatThrownBy(() -> document.getReasonCodes().add("MUTATION"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noForbiddenVerdictFraudOrFinalOutcomeFields() {
        assertThat(List.of(SuspiciousTransactionDocument.class.getDeclaredFields()).stream()
                .map(Field::getName)
                .map(name -> name.toLowerCase(Locale.ROOT)))
                .noneMatch(name -> name.contains("fraudconfirmed")
                        || name.contains("confirmedfraud")
                        || name.contains("verdict")
                        || name.contains("finaloutcome")
                        || name.contains("analystdecision")
                        || name.contains("legalproof"));
    }

    @Test
    void evidenceSnapshotAndLinkedCaseFieldsAreNotPresentInFdp60() {
        assertThat(List.of(SuspiciousTransactionDocument.class.getDeclaredFields()).stream().map(Field::getName))
                .doesNotContain("evidenceSnapshot", "linkedCaseId");
    }
}
