package com.frauddetection.alert.feedback;

import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

final class FraudFeedbackNonDecisioningOpenApiPolicy {

    private static final Set<String> FORBIDDEN_ACTIVE_ACTIONS = Set.of(
            "APPROVEPAYMENT",
            "DECLINEPAYMENT",
            "BLOCKTRANSACTION",
            "AUTHORIZEPAYMENT"
    );
    private static final Set<String> ALLOWED_NEGATED_LIMITATIONS = Set.of("DOES_NOT_AUTHORIZE_PAYMENTS");
    private static final Set<String> ACTIVE_SCALAR_FIELDS = Set.of(
            "operationId",
            "action",
            "authority",
            "authorities"
    );
    private static final Set<String> MACHINE_VALUE_FIELDS = Set.of("enum", "default", "example", "examples", "value");
    private static final Set<String> PAYMENT_ACTIONS = Set.of("AUTHORIZE", "APPROVE", "DECLINE", "BLOCK");

    private FraudFeedbackNonDecisioningOpenApiPolicy() {
    }

    static void assertNonDecisioningContract(String openApi) {
        assertStructuredTokensAllowed(structuredMachineTokens(openApi));
    }

    static void assertStructuredTokensAllowed(List<String> tokens) {
        List<String> forbiddenCodes = tokens.stream()
                .filter(token -> FORBIDDEN_ACTIVE_ACTIONS.contains(semanticToken(token)))
                .sorted()
                .toList();
        assertThat(forbiddenCodes).isEmpty();
        List<String> untrustedPaymentCodes = tokens.stream()
                .filter(FraudFeedbackNonDecisioningOpenApiPolicy::looksLikePaymentDecisioningCode)
                .filter(token -> !ALLOWED_NEGATED_LIMITATIONS.contains(token))
                .sorted()
                .toList();
        assertThat(untrustedPaymentCodes).isEmpty();
    }

    private static boolean looksLikePaymentDecisioningCode(String token) {
        String compact = semanticToken(token);
        return compact.contains("PAYMENT")
                && PAYMENT_ACTIONS.stream().anyMatch(compact::contains);
    }

    private static List<String> structuredMachineTokens(String yaml) {
        YamlMapFactoryBean yamlFactory = new YamlMapFactoryBean();
        yamlFactory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> parsed = yamlFactory.getObject();
        List<String> tokens = new ArrayList<>();
        collectStructuredValues(parsed, "", false, tokens);
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private static void collectStructuredValues(Object value, String fieldName, boolean activeContext, List<String> tokens) {
        boolean inspect = activeContext || shouldInspectField(fieldName);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (inspect && shouldInspectMapKey(key)) {
                    tokens.add(key);
                }
                collectStructuredValues(entry.getValue(), key, inspect, tokens);
            }
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                collectStructuredValues(item, fieldName, inspect, tokens);
            }
            return;
        }
        if (value instanceof String text && shouldCollectScalar(fieldName, inspect, text)) {
            tokens.add(text);
        }
    }

    private static boolean shouldInspectField(String fieldName) {
        return ACTIVE_SCALAR_FIELDS.contains(fieldName)
                || MACHINE_VALUE_FIELDS.contains(fieldName)
                || fieldName.startsWith("x-");
    }

    private static boolean shouldInspectMapKey(String key) {
        return key.startsWith("x-");
    }

    private static boolean shouldCollectScalar(String fieldName, boolean activeContext, String text) {
        if (text.isBlank()) {
            return false;
        }
        if (ACTIVE_SCALAR_FIELDS.contains(fieldName)) {
            return true;
        }
        if (MACHINE_VALUE_FIELDS.contains(fieldName) || fieldName.startsWith("x-")) {
            return looksLikeMachineValue(text);
        }
        return activeContext && looksLikeMachineValue(text);
    }

    private static boolean looksLikeMachineValue(String text) {
        if (text.length() > 64) {
            return false;
        }
        int words = 0;
        boolean inWord = false;
        boolean segmentedMachineCode = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                if (!inWord) {
                    words++;
                    inWord = true;
                }
                continue;
            }
            inWord = false;
            if (character != '_' && character != '-' && character != ' ') {
                return false;
            }
            if (character == '_' || character == '-') {
                segmentedMachineCode = true;
            }
        }
        return words > 0 && (words <= 4 || segmentedMachineCode);
    }

    private static String semanticToken(String value) {
        StringBuilder compact = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                compact.append(Character.toUpperCase(character));
            }
        }
        return compact.toString();
    }
}
