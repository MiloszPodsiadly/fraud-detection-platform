package com.frauddetection.scoring.orchestration.aggregation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class FraudEngineAggregationSafety {
    private static final Set<String> FORBIDDEN_TEXT = Set.of(
            "transactionid",
            "transaction id",
            "customerid",
            "customer id",
            "accountid",
            "account id",
            "cardid",
            "card id",
            "merchantid",
            "merchant id",
            "rawpayload",
            "raw payload",
            "rawfeature",
            "raw feature",
            "featurevector",
            "feature vector",
            "endpoint",
            "http://",
            "https://",
            "token",
            "secret",
            "stacktrace",
            "stack trace",
            "exception",
            "bearer",
            "password",
            "api key"
    );

    private FraudEngineAggregationSafety() {
    }

    static boolean isSafe(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return FORBIDDEN_TEXT.stream().noneMatch(normalized::contains)
                && value.chars().noneMatch(Character::isISOControl);
    }

    static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    static void addWarning(
            List<FraudEngineAggregationWarning> warnings,
            FraudEngineAggregationPolicy policy,
            String engineId,
            FraudEngineAggregationWarningCode code
    ) {
        if (warnings.size() < policy.maxWarnings()) {
            warnings.add(new FraudEngineAggregationWarning(engineId, code));
        }
    }
}
