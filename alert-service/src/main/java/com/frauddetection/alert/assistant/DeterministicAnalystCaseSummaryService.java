package com.frauddetection.alert.assistant;

import com.frauddetection.alert.config.AssistantMode;
import com.frauddetection.alert.config.AssistantProperties;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeterministicAnalystCaseSummaryService implements AnalystCaseSummaryUseCase {

    private static final String DEVICE_NOVELTY_REASON = "DEVICE_NOVELTY";
    private static final String COUNTRY_MISMATCH_REASON = "COUNTRY_MISMATCH";
    private static final String PROXY_OR_VPN_REASON = "PROXY_OR_VPN";
    private static final String HIGH_VELOCITY_REASON = "HIGH_VELOCITY";
    private static final String TRANSACTION_VELOCITY_REASON = "TRANSACTION_VELOCITY";
    private static final String HIGH_TRANSACTION_AMOUNT_REASON = "HIGH_TRANSACTION_AMOUNT";
    private static final String TRANSACTION_VELOCITY_PER_MINUTE = "transactionVelocityPerMinute";
    private static final String DEVICE_NOVELTY = "deviceNovelty";
    private static final String COUNTRY_MISMATCH = "countryMismatch";
    private static final String PROXY_OR_VPN_DETECTED = "proxyOrVpnDetected";

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
                moneyPlnValue(snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN)),
                numberValue(snapshot.get(TRANSACTION_VELOCITY_PER_MINUTE)),
                integerValue(snapshot.get("merchantFrequency7d")),
                booleanValue(snapshot.get(DEVICE_NOVELTY)),
                booleanValue(snapshot.get(COUNTRY_MISMATCH)),
                booleanValue(snapshot.get(PROXY_OR_VPN_DETECTED)),
                null
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
        if (contains(alert.reasonCodes(), COUNTRY_MISMATCH_REASON) || contains(alert.reasonCodes(), DEVICE_NOVELTY_REASON)) {
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
        Map<String, Object> snapshot = alert.featureSnapshot() == null ? Map.of() : alert.featureSnapshot();
        evidence.put("alertStatus", alert.alertStatus());
        evidence.put("modelName", value(alert.scoreDetails(), "modelName"));
        evidence.put("modelVersion", value(alert.scoreDetails(), "modelVersion"));
        evidence.put("riskLevel", alert.riskLevel() == null ? null : alert.riskLevel().name());
        evidence.put("reasonCodes", alert.reasonCodes() == null ? List.of() : alert.reasonCodes());
        evidence.put("recentTransactionCount", integerValue(snapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT)));
        evidence.put("recentAmountSumPln", moneyPlnValue(snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN)));
        evidence.put("transactionVelocityPerMinute", numberValue(snapshot.get(TRANSACTION_VELOCITY_PER_MINUTE)));
        evidence.put("deviceNovelty", booleanValue(snapshot.get(DEVICE_NOVELTY)));
        evidence.put("countryMismatch", booleanValue(snapshot.get(COUNTRY_MISMATCH)));
        evidence.put("proxyOrVpnDetected", booleanValue(snapshot.get(PROXY_OR_VPN_DETECTED)));
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
            case DEVICE_NOVELTY_REASON, DEVICE_NOVELTY -> evidence.put(DEVICE_NOVELTY, snapshot.get(DEVICE_NOVELTY));
            case COUNTRY_MISMATCH_REASON, COUNTRY_MISMATCH -> {
                evidence.put(COUNTRY_MISMATCH, snapshot.get(COUNTRY_MISMATCH));
                evidence.put("homeCountryCode", alert.customerContext() == null ? null : alert.customerContext().homeCountryCode());
                evidence.put("transactionCountryCode", alert.locationInfo() == null ? null : alert.locationInfo().countryCode());
            }
            case PROXY_OR_VPN_REASON, PROXY_OR_VPN_DETECTED -> evidence.put(PROXY_OR_VPN_DETECTED, snapshot.get(PROXY_OR_VPN_DETECTED));
            case HIGH_VELOCITY_REASON, TRANSACTION_VELOCITY_REASON, TRANSACTION_VELOCITY_PER_MINUTE ->
                    evidence.put(TRANSACTION_VELOCITY_PER_MINUTE, snapshot.get(TRANSACTION_VELOCITY_PER_MINUTE));
            case HIGH_TRANSACTION_AMOUNT_REASON ->
                    evidence.put("recentAmountSumPln", moneyPlnValue(snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN)));
            default -> {
                evidence.put("riskLevel", alert.riskLevel() == null ? "UNKNOWN" : alert.riskLevel().name());
                evidence.put("reasonCode", reasonCode);
            }
        }
        return evidence;
    }

    private String analystLabel(String reasonCode) {
        return switch (reasonCode) {
            case DEVICE_NOVELTY_REASON, DEVICE_NOVELTY -> "New or unusual device";
            case COUNTRY_MISMATCH_REASON, COUNTRY_MISMATCH -> "Country mismatch";
            case PROXY_OR_VPN_REASON, PROXY_OR_VPN_DETECTED -> "Proxy or VPN detected";
            case HIGH_VELOCITY_REASON, TRANSACTION_VELOCITY_REASON, TRANSACTION_VELOCITY_PER_MINUTE -> "Transaction velocity";
            case HIGH_TRANSACTION_AMOUNT_REASON -> "High amount activity";
            default -> reasonCode.replace('_', ' ');
        };
    }

    private String explanation(String reasonCode) {
        return switch (reasonCode) {
            case DEVICE_NOVELTY_REASON, DEVICE_NOVELTY -> "The transaction used a device signal that differs from the customer's known behavior.";
            case COUNTRY_MISMATCH_REASON, COUNTRY_MISMATCH -> "The transaction location differs from the customer's expected country context.";
            case PROXY_OR_VPN_REASON, PROXY_OR_VPN_DETECTED -> "Network indicators suggest anonymized or proxied access.";
            case HIGH_VELOCITY_REASON, TRANSACTION_VELOCITY_REASON, TRANSACTION_VELOCITY_PER_MINUTE -> "Recent transaction frequency is elevated for this customer.";
            case HIGH_TRANSACTION_AMOUNT_REASON -> "The transaction or recent amount accumulation is materially higher than baseline traffic.";
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

    private Money moneyPlnValue(Object value) {
        BigDecimal amount = decimalValue(value);
        return amount == null ? null : new Money(amount, "PLN");
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Integer integer) {
            return BigDecimal.valueOf(integer.longValue());
        }
        if (value instanceof Long longValue) {
            return BigDecimal.valueOf(longValue);
        }
        if (value instanceof Double doubleValue && Double.isFinite(doubleValue)) {
            return BigDecimal.valueOf(doubleValue);
        }
        return null;
    }

}
