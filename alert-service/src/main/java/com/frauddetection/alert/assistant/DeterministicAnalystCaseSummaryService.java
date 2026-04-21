package com.frauddetection.alert.assistant;

import com.frauddetection.alert.config.AssistantMode;
import com.frauddetection.alert.config.AssistantProperties;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeterministicAnalystCaseSummaryService implements AnalystCaseSummaryUseCase {

    private final AlertManagementUseCase alertManagementUseCase;
    private final AssistantProperties assistantProperties;
    private final OllamaCaseNarrativeClient ollamaCaseNarrativeClient;

    public DeterministicAnalystCaseSummaryService(
            AlertManagementUseCase alertManagementUseCase,
            AssistantProperties assistantProperties,
            OllamaCaseNarrativeClient ollamaCaseNarrativeClient
    ) {
        this.alertManagementUseCase = alertManagementUseCase;
        this.assistantProperties = assistantProperties;
        this.ollamaCaseNarrativeClient = ollamaCaseNarrativeClient;
    }

    @Override
    public AnalystCaseSummaryResponse generateSummary(AnalystCaseSummaryRequest request) {
        AlertCase alert = alertManagementUseCase.getAlert(request.alertId());

        AnalystCaseSummaryResponse deterministicSummary = new AnalystCaseSummaryResponse(
                alert.alertId(),
                alert.transactionId(),
                alert.customerId(),
                alert.correlationId(),
                transactionSummary(alert),
                fraudReasons(alert),
                behaviorSummary(alert),
                recommendedNextAction(alert),
                supportingEvidence(alert),
                Instant.now()
        );

        if (assistantProperties.mode() != AssistantMode.OLLAMA) {
            return deterministicSummary;
        }

        return ollamaCaseNarrativeClient.generate(alert, deterministicSummary)
                .map(narrative -> withLlmNarrative(deterministicSummary, narrative))
                .orElse(deterministicSummary);
    }

    private TransactionSummary transactionSummary(AlertCase alert) {
        return new TransactionSummary(
                alert.transactionId(),
                alert.alertTimestamp(),
                alert.transactionAmount(),
                alert.merchantInfo() == null ? null : alert.merchantInfo().merchantId(),
                alert.merchantInfo() == null ? null : alert.merchantInfo().merchantName(),
                alert.merchantInfo() == null ? null : alert.merchantInfo().merchantCategory(),
                alert.merchantInfo() == null ? null : alert.merchantInfo().channel(),
                alert.locationInfo() == null ? null : alert.locationInfo().countryCode(),
                alert.fraudScore(),
                alert.riskLevel()
        );
    }

    private List<FraudReasonSummary> fraudReasons(AlertCase alert) {
        List<String> reasonCodes = alert.reasonCodes() == null ? List.of() : alert.reasonCodes();
        Map<String, Object> contributions = mapValue(alert.scoreDetails(), "featureContributions");
        List<FraudReasonSummary> summaries = new ArrayList<>();

        for (String reasonCode : reasonCodes) {
            summaries.add(new FraudReasonSummary(
                    reasonCode,
                    analystLabel(reasonCode),
                    explanation(reasonCode),
                    numberValue(contributions.get(reasonCode)),
                    evidenceFor(reasonCode, alert)
            ));
        }

        if (summaries.isEmpty()) {
            summaries.add(new FraudReasonSummary(
                    "MODEL_SCORE",
                    "Elevated model score",
                    "The alert was created because the final fraud score crossed the configured alert threshold.",
                    alert.fraudScore(),
                    Map.of("riskLevel", alert.riskLevel() == null ? "UNKNOWN" : alert.riskLevel().name())
            ));
        }

        return summaries;
    }

    private CustomerRecentBehaviorSummary behaviorSummary(AlertCase alert) {
        Map<String, Object> snapshot = alert.featureSnapshot() == null ? Map.of() : alert.featureSnapshot();
        return new CustomerRecentBehaviorSummary(
                alert.customerId(),
                alert.customerContext() == null ? null : alert.customerContext().segment(),
                alert.customerContext() == null ? null : alert.customerContext().accountAgeDays(),
                integerValue(snapshot.get("recentTransactionCount")),
                alert.transactionAmount() == null ? null : moneyValue(snapshot.get("recentAmountSum"), alert.transactionAmount().currency()),
                numberValue(snapshot.get("transactionVelocityPerMinute")),
                integerValue(snapshot.get("merchantFrequency7d")),
                booleanValue(snapshot.get("deviceNovelty")),
                booleanValue(snapshot.get("countryMismatch")),
                booleanValue(snapshot.get("proxyOrVpnDetected")),
                null,
                snapshot
        );
    }

    private RecommendedNextAction recommendedNextAction(AlertCase alert) {
        if (alert.riskLevel() == RiskLevel.CRITICAL) {
            return new RecommendedNextAction(
                    "ESCALATE_AND_VERIFY",
                    "Escalate for immediate review",
                    "Critical risk and strong fraud signals require fast containment before analyst closure.",
                    List.of("Verify customer contact signals", "Review device and location history", "Check recent merchant activity")
            );
        }
        if (contains(alert.reasonCodes(), "COUNTRY_MISMATCH") || contains(alert.reasonCodes(), "DEVICE_NOVELTY")) {
            return new RecommendedNextAction(
                    "STEP_UP_REVIEW",
                    "Review identity and device signals",
                    "The case contains account access or location signals that are useful for analyst confirmation.",
                    List.of("Compare transaction country with home country", "Inspect device novelty", "Validate proxy or VPN indicators")
            );
        }
        return new RecommendedNextAction(
                "STANDARD_REVIEW",
                "Complete standard fraud review",
                "The score is elevated, but the available reasons do not require a specialized escalation path.",
                List.of("Review reason codes", "Check transaction amount and merchant", "Submit the final analyst decision")
        );
    }

    private Map<String, Object> supportingEvidence(AlertCase alert) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("alertStatus", alert.alertStatus());
        evidence.put("scoreDetails", alert.scoreDetails() == null ? Map.of() : alert.scoreDetails());
        evidence.put("featureSnapshot", alert.featureSnapshot() == null ? Map.of() : alert.featureSnapshot());
        evidence.put("modelName", value(alert.scoreDetails(), "modelName"));
        evidence.put("modelVersion", value(alert.scoreDetails(), "modelVersion"));
        return evidence;
    }

    private AnalystCaseSummaryResponse withLlmNarrative(AnalystCaseSummaryResponse summary, OllamaCaseNarrative narrative) {
        Map<String, Object> supportingEvidence = new LinkedHashMap<>(summary.supportingEvidence());
        supportingEvidence.put("assistantMode", AssistantMode.OLLAMA.name());
        supportingEvidence.put("llmNarrative", Map.of(
                "overview", narrative.overview() == null ? "" : narrative.overview(),
                "keyObservations", narrative.keyObservations() == null ? List.of() : narrative.keyObservations(),
                "recommendedActionRationale", narrative.recommendedActionRationale() == null ? "" : narrative.recommendedActionRationale(),
                "uncertainty", narrative.uncertainty() == null ? "" : narrative.uncertainty(),
                "modelName", narrative.modelName(),
                "modelVersion", narrative.modelVersion()
        ));

        RecommendedNextAction originalAction = summary.recommendedNextAction();
        RecommendedNextAction action = new RecommendedNextAction(
                originalAction.actionCode(),
                originalAction.title(),
                narrative.recommendedActionRationale() == null || narrative.recommendedActionRationale().isBlank()
                        ? originalAction.rationale()
                        : narrative.recommendedActionRationale(),
                originalAction.suggestedReviewSteps()
        );

        return new AnalystCaseSummaryResponse(
                summary.alertId(),
                summary.transactionId(),
                summary.customerId(),
                summary.correlationId(),
                summary.transactionSummary(),
                summary.mainFraudReasons(),
                summary.customerRecentBehaviorSummary(),
                action,
                supportingEvidence,
                summary.generatedAt()
        );
    }

    private Map<String, Object> evidenceFor(String reasonCode, AlertCase alert) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, Object> snapshot = alert.featureSnapshot() == null ? Map.of() : alert.featureSnapshot();
        switch (reasonCode) {
            case "DEVICE_NOVELTY", "deviceNovelty" -> evidence.put("deviceNovelty", snapshot.get("deviceNovelty"));
            case "COUNTRY_MISMATCH", "countryMismatch" -> {
                evidence.put("countryMismatch", snapshot.get("countryMismatch"));
                evidence.put("homeCountryCode", alert.customerContext() == null ? null : alert.customerContext().homeCountryCode());
                evidence.put("transactionCountryCode", alert.locationInfo() == null ? null : alert.locationInfo().countryCode());
            }
            case "PROXY_OR_VPN", "proxyOrVpnDetected" -> evidence.put("proxyOrVpnDetected", snapshot.get("proxyOrVpnDetected"));
            case "HIGH_VELOCITY", "TRANSACTION_VELOCITY", "transactionVelocityPerMinute" -> evidence.put("transactionVelocityPerMinute", snapshot.get("transactionVelocityPerMinute"));
            default -> evidence.put("featureSnapshot", snapshot);
        }
        return evidence;
    }

    private String analystLabel(String reasonCode) {
        return switch (reasonCode) {
            case "DEVICE_NOVELTY", "deviceNovelty" -> "New or unusual device";
            case "COUNTRY_MISMATCH", "countryMismatch" -> "Country mismatch";
            case "PROXY_OR_VPN", "proxyOrVpnDetected" -> "Proxy or VPN detected";
            case "HIGH_VELOCITY", "TRANSACTION_VELOCITY", "transactionVelocityPerMinute" -> "Transaction velocity";
            case "HIGH_TRANSACTION_AMOUNT", "recentAmountSum" -> "High amount activity";
            default -> reasonCode.replace('_', ' ');
        };
    }

    private String explanation(String reasonCode) {
        return switch (reasonCode) {
            case "DEVICE_NOVELTY", "deviceNovelty" -> "The transaction used a device signal that differs from the customer's known behavior.";
            case "COUNTRY_MISMATCH", "countryMismatch" -> "The transaction location differs from the customer's expected country context.";
            case "PROXY_OR_VPN", "proxyOrVpnDetected" -> "Network indicators suggest anonymized or proxied access.";
            case "HIGH_VELOCITY", "TRANSACTION_VELOCITY", "transactionVelocityPerMinute" -> "Recent transaction frequency is elevated for this customer.";
            case "HIGH_TRANSACTION_AMOUNT", "recentAmountSum" -> "The transaction or recent amount accumulation is materially higher than baseline traffic.";
            default -> "This signal contributed to the alert score and should be reviewed with the supporting evidence.";
        };
    }

    private boolean contains(List<String> values, String expected) {
        return values != null && values.contains(expected);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> source, String key) {
        Object value = value(source, key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private Double numberValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private com.frauddetection.common.events.model.Money moneyValue(Object value, String fallbackCurrency) {
        if (value instanceof com.frauddetection.common.events.model.Money money) {
            return money;
        }
        if (value instanceof Map<?, ?> map) {
            Object amount = map.get("amount");
            Object currency = map.get("currency");
            Double numericAmount = numberValue(amount);
            if (numericAmount != null) {
                return new com.frauddetection.common.events.model.Money(
                        java.math.BigDecimal.valueOf(numericAmount),
                        currency == null ? fallbackCurrency : currency.toString()
                );
            }
        }
        if (value instanceof Number number) {
            return new com.frauddetection.common.events.model.Money(java.math.BigDecimal.valueOf(number.doubleValue()), fallbackCurrency);
        }
        return null;
    }
}
