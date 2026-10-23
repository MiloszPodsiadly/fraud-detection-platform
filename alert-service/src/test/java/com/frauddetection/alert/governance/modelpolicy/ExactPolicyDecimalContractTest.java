package com.frauddetection.alert.governance.modelpolicy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactPolicyDecimalContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exactStringTransportPreservesScaleAndRejectsJsonNumbers() {
        var command = mapper.readTree("""
                {"draft":{"criteria":[{"thresholds":{"maximumRate":"0.12345678901234567890"}}]}}
                """);
        ExactPolicyDecimalContract.requireDraftTransport(command);
        assertThat(ExactPolicyDecimalContract.parse("0.12345678901234567890", "maximumRate"))
                .isEqualTo(new BigDecimal("0.12345678901234567890"));
        assertThatThrownBy(() -> ExactPolicyDecimalContract.requireDraftTransport(mapper.readTree("""
                {"draft":{"criteria":[{"thresholds":{"maximumRate":0.12345678901234567890}}]}}
                """))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decimalLexicalAndBoundsContractIsExplicit() {
        for (String valid : new String[] {"0", "1", "0.12345678901234567890",
                "0.000000000000000000000001", "1234567890123456789012345678901234"}) {
            assertThat(ExactPolicyDecimalContract.parse(valid, "limit").toPlainString()).isEqualTo(valid);
        }
        for (String invalid : new String[] {"-0", "-1", "1e-3", "1E+2", "1,2", "", "NaN",
                "Infinity", "0.0000000000000000000000001", "12345678901234567890123456789012345"}) {
            assertThatThrownBy(() -> ExactPolicyDecimalContract.parse(invalid, "limit"))
                    .as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
