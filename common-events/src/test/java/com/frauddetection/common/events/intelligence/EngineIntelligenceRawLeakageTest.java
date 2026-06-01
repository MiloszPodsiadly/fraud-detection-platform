package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceRawLeakageTest {
    private static final List<String> FORBIDDEN = List.of(
            "transactionId", "transaction_id", "txn_id", "customerId", "customer_id", "cust_id",
            "accountId", "account_id", "acct_id", "cardId", "card_id", "merchantId", "merchant_id",
            "rawPayload", "payload", "featureVector", "raw_feature_vector", "endpoint", "model_endpoint",
            "http://", "https://", "token", "accessToken", "bearerToken", "secret", "apiKey", "password",
            "stackTrace", "stack_trace", "exception", "exceptionMessage", "debug"
    );

    @Test
    void serializedEngineIntelligenceDoesNotContainForbiddenRawStrings() throws Exception {
        String json = EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary());

        FORBIDDEN.forEach(value -> assertThat(json).doesNotContainIgnoringCase(value));
    }

    @Test
    void publicDtoRejectsForbiddenReasonCodes() {
        assertThatThrownBy(() -> EngineIntelligenceTestSupport.engine(List.of("transactionId")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicDtoRejectsForbiddenSignalCodes() {
        assertThatThrownBy(() -> new EngineIntelligenceDiagnosticSignal(
                "rules.primary",
                com.frauddetection.common.events.engine.FraudEngineType.RULES,
                com.frauddetection.common.events.engine.FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                "token"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicDtoRejectsForbiddenWarningCodes() {
        assertThatThrownBy(() -> EngineIntelligenceWarningCode.valueOf("TOKEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedEngineIntelligenceDoesNotDuplicateTransactionCustomerAccountCardMerchantIdentifiers() throws Exception {
        String json = EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary());

        assertThat(json).doesNotContainIgnoringCase("transactionId", "customerId", "accountId", "cardId", "merchantId");
    }
}
