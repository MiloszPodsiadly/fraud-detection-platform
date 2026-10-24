package com.frauddetection.alert.governance.modelpolicy;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** New draft requests exchange exact decimal strings; persisted and signed policies remain BigDecimal. */
public final class ExactPolicyDecimalContract {
    public static final int MAX_PRECISION = 34;
    public static final int MAX_SCALE = 24;
    private static final Pattern PLAIN_NONNEGATIVE = Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]+)?$");

    private ExactPolicyDecimalContract() { }

    public static void requireDraftTransport(JsonNode command) {
        JsonNode criteria = command == null ? null : command.path("draft").path("criteria");
        if (criteria == null || !criteria.isArray())
            throw new IllegalArgumentException("Policy draft criteria are required");
        for (JsonNode rule : criteria) {
            JsonNode thresholds = rule.path("thresholds");
            if (!thresholds.isObject())
                throw new IllegalArgumentException("Policy thresholds must be an object");
            for (var entry : thresholds.properties()) {
                JsonNode value = entry.getValue();
                if (!value.isTextual())
                    throw new IllegalArgumentException("Policy threshold " + entry.getKey()
                            + " must be an exact decimal string");
                parse(value.asText(), entry.getKey());
            }
        }
    }

    public static BigDecimal parse(String text, String field) {
        if (text == null || text.length() > 60 || !PLAIN_NONNEGATIVE.matcher(text).matches())
            throw new IllegalArgumentException("Policy threshold " + field + " must be a plain nonnegative decimal");
        BigDecimal value = new BigDecimal(text);
        if (value.precision() > MAX_PRECISION || value.scale() > MAX_SCALE)
            throw new IllegalArgumentException("Policy threshold " + field + " exceeds decimal precision or scale");
        return value;
    }

    public static void requireNewDraftValue(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.precision() > MAX_PRECISION
                || value.scale() < 0 || value.scale() > MAX_SCALE)
            throw new IllegalArgumentException("Policy threshold " + field + " exceeds exact decimal contract");
    }
}
