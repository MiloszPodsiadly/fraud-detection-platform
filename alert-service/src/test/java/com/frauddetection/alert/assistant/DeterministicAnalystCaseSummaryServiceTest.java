package com.frauddetection.alert.assistant;

import com.frauddetection.alert.config.AssistantMode;
import com.frauddetection.alert.config.AssistantProperties;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicAnalystCaseSummaryServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void assistantSummaryDoesNotExposeFeatureSnapshot() throws Exception {
        String json = summaryJson(alertWithRawInputs());

        assertThat(json).doesNotContain("featureSnapshot", "rawSnapshotSecret");
    }

    @Test
    void assistantSummaryDoesNotExposeNormalizedFeatureVector() throws Exception {
        String json = summaryJson(alertWithRawInputs());

        assertThat(json).doesNotContain("normalizedFeatures", "normalizedFeatureSecret");
    }

    @Test
    void assistantSummaryDoesNotExposeArbitraryScoreDetails() throws Exception {
        String json = summaryJson(alertWithRawInputs());

        assertThat(json).doesNotContain("scoreDetails", "featureCompatibility", "featureContributions", "arbitraryModelSecret");
    }

    @Test
    void assistantSummaryUsesRecentAmountSumPln() throws Exception {
        JsonNode root = objectMapper.readTree(summaryJson(alertWithRawInputs()));

        JsonNode recentAmount = root.path("customerRecentBehaviorSummary").path("recentAmountSumPln");
        assertThat(recentAmount.path("amount").decimalValue()).isEqualByComparingTo("20000.00");
        assertThat(recentAmount.path("currency").textValue()).isEqualTo("PLN");
        assertThat(root.path("supportingEvidence").path("recentAmountSumPln").path("currency").textValue())
                .isEqualTo("PLN");
    }

    @Test
    void assistantSummaryMixedCurrencyHistoryDoesNotInventCurrentTransactionCurrency() throws Exception {
        JsonNode root = objectMapper.readTree(summaryJson(alertWithRawInputs()));

        assertThat(root.path("transactionSummary").path("amount").path("currency").textValue()).isEqualTo("USD");
        assertThat(root.path("customerRecentBehaviorSummary").path("recentAmountSumPln").path("currency").textValue())
                .isEqualTo("PLN");
        assertThat(root.toString()).doesNotContain("\"recentAmountSum\":{\"amount\":999.0,\"currency\":\"USD\"");
    }

    private String summaryJson(AlertCase alert) throws Exception {
        AlertManagementUseCase alertManagementUseCase = mock(AlertManagementUseCase.class);
        when(alertManagementUseCase.getAlert(alert.alertId())).thenReturn(alert);
        DeterministicAnalystCaseSummaryService service = new DeterministicAnalystCaseSummaryService(
                alertManagementUseCase,
                new AssistantProperties(AssistantMode.DETERMINISTIC, null, null, null, null),
                mock(OllamaCaseNarrativeClient.class)
        );

        AnalystCaseSummaryResponse summary = service.generateSummary(
                new AnalystCaseSummaryRequest(alert.alertId(), "analyst-1", List.of(), Map.of())
        );
        return objectMapper.writeValueAsString(summary);
    }

    private AlertCase alertWithRawInputs() {
        return new AlertCase(
                "alert-assistant-1",
                "txn-assistant-1",
                "cust-assistant-1",
                "corr-assistant-1",
                Instant.parse("2026-09-19T10:00:00Z"),
                Instant.parse("2026-09-19T10:00:02Z"),
                RiskLevel.HIGH,
                0.82d,
                AlertStatus.OPEN,
                "High-risk transaction flagged for review.",
                List.of("MODEL_HIGH_RISK", "HIGH_TRANSACTION_AMOUNT"),
                new Money(new BigDecimal("100.00"), "USD"),
                new MerchantInfo("m-1", "Merchant", "5732", "electronics", "PL", "ECOMMERCE", false, Map.of()),
                new DeviceInfo("d-1", "fp", "127.0.0.1", "agent", "web", "browser", true, false, false, Map.of()),
                new LocationInfo("PL", "MZ", "Warsaw", "00-001", 52.2297d, 21.0122d, "Europe/Warsaw", false),
                new CustomerContext(
                        "cust-assistant-1",
                        "acct-assistant-1",
                        "retail",
                        "example.test",
                        365,
                        true,
                        true,
                        "PL",
                        "USD",
                        List.of("d-1"),
                        Map.of()
                ),
                Map.of(
                        "modelName", "python-logistic-fraud-model",
                        "modelVersion", "test-version",
                        "normalizedFeatures", Map.of("raw", "normalizedFeatureSecret"),
                        "featureCompatibility", Map.of("raw", "arbitraryModelSecret"),
                        "featureContributions", Map.of("MODEL_HIGH_RISK", 0.4d, "raw", "arbitraryModelSecret")
                ),
                Map.ofEntries(
                        Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2),
                        Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00")),
                        Map.entry("recentAmountSum", new Money(new BigDecimal("999.00"), "USD")),
                        Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 2.0d),
                        Map.entry(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 3),
                        Map.entry(FraudFeatureContract.DEVICE_NOVELTY, true),
                        Map.entry(FraudFeatureContract.COUNTRY_MISMATCH, false),
                        Map.entry(FraudFeatureContract.PROXY_OR_VPN_DETECTED, false),
                        Map.entry("rawSnapshotSecret", "must-not-leak")
                ),
                null,
                null,
                null,
                List.of(),
                null
        );
    }
}
