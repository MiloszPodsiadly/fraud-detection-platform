package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionReasonCodesDefensivelyCopiedTest {

    @Test
    void setterAndGetterAreDefensive() {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();
        List<String> reasonCodes = new ArrayList<>(List.of("HIGH_AMOUNT"));

        document.setReasonCodes(reasonCodes);
        reasonCodes.clear();

        assertThat(document.getReasonCodes()).containsExactly("HIGH_AMOUNT");
    }
}
