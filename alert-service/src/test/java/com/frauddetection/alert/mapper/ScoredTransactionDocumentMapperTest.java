package com.frauddetection.alert.mapper;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoredTransactionDocumentMapperTest {

    private final ScoredTransactionDocumentMapper mapper = new ScoredTransactionDocumentMapper();

    @Test
    void shouldPopulateNormalizedIndexedSearchFields() {
        var document = mapper.toDocument(new TransactionScoredEvent(
                "event-1",
                " TxN-ABC-123 ",
                "correlation-1",
                " Customer-123 ",
                "account-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                new Money(BigDecimal.TEN, " PLN "),
                new MerchantInfo(" Merchant-9 ", "Sensitive merchant", "5411", "GROCERY", "PL", "ECOMMERCE", false, Map.of()),
                null,
                null,
                null,
                0.91,
                RiskLevel.CRITICAL,
                "strategy",
                "model",
                "v1",
                Instant.parse("2026-01-01T00:00:01Z"),
                List.of("DEVICE_NOVELTY"),
                Map.of(),
                Map.of(),
                true
        ));

        assertThat(document.getTransactionIdSearch()).isEqualTo("txn-abc-123");
        assertThat(document.getCustomerIdSearch()).isEqualTo("customer-123");
        assertThat(document.getMerchantIdSearch()).isEqualTo("merchant-9");
        assertThat(document.getCurrencySearch()).isEqualTo("pln");
    }
}
