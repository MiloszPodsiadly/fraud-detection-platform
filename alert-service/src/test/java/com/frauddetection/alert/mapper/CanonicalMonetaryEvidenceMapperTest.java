package com.frauddetection.alert.mapper;

import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalMonetaryEvidenceMapperTest {

    @Test
    void mapsOnlyBoundedCanonicalDecimalEvidence() {
        assertThat(CanonicalMonetaryEvidenceMapper.currentTransactionAmountPln(Map.of(
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("123.45")
        ))).isEqualByComparingTo("123.45");

        assertThat(CanonicalMonetaryEvidenceMapper.currentTransactionAmountPln(Map.of())).isNull();
        assertThat(CanonicalMonetaryEvidenceMapper.currentTransactionAmountPln(Map.of(
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, "123.45"
        ))).isNull();
        assertThat(CanonicalMonetaryEvidenceMapper.currentTransactionAmountPln(Map.of(
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("-0.01")
        ))).isNull();
        assertThat(CanonicalMonetaryEvidenceMapper.currentTransactionAmountPln(Map.of(
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN,
                FraudFeatureValueBoundsContract.MAX_RECENT_AMOUNT_SUM_PLN.add(new BigDecimal("0.01"))
        ))).isNull();
    }
}
