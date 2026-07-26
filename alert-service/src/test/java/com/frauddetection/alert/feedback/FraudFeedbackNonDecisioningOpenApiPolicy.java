package com.frauddetection.alert.feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

final class FraudFeedbackNonDecisioningOpenApiPolicy {

    private static final Set<String> FORBIDDEN_ACTIVE_ACTIONS = Set.of(
            "APPROVE_PAYMENT",
            "DECLINE_PAYMENT",
            "BLOCK_TRANSACTION",
            "AUTHORIZE_PAYMENT"
    );
    private static final Set<String> ALLOWED_NEGATED_LIMITATIONS = Set.of("DOES_NOT_AUTHORIZE_PAYMENTS");
    private static final Pattern MACHINE_CODE = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,}\\b");

    private FraudFeedbackNonDecisioningOpenApiPolicy() {
    }

    static void assertNonDecisioningContract(String openApi) {
        assertStructuredTokensAllowed(structuredMachineTokens(openApi));
    }

    static void assertStructuredTokensAllowed(List<String> tokens) {
        assertThat(tokens).doesNotContainAnyElementsOf(FORBIDDEN_ACTIVE_ACTIONS);
        List<String> untrustedPaymentCodes = tokens.stream()
                .filter(FraudFeedbackNonDecisioningOpenApiPolicy::looksLikePaymentDecisioningCode)
                .filter(token -> !ALLOWED_NEGATED_LIMITATIONS.contains(token))
                .sorted()
                .toList();
        assertThat(untrustedPaymentCodes).isEmpty();
    }

    private static boolean looksLikePaymentDecisioningCode(String token) {
        String compact = token.replace("_", "");
        return compact.contains("PAYMENT")
                && (compact.contains("AUTHORIZE")
                || compact.contains("APPROVE")
                || compact.contains("DECLINE")
                || compact.contains("BLOCK"));
    }

    private static List<String> structuredMachineTokens(String yaml) {
        List<String> tokens = new ArrayList<>();
        boolean inMachineContext = false;
        int contextIndent = -1;
        for (String line : yaml.split("\\R")) {
            String trimmed = line.trim();
            int indent = line.indexOf(trimmed);
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (inMachineContext && indent <= contextIndent && !trimmed.startsWith("- ")) {
                inMachineContext = false;
            }
            if (trimmed.startsWith("enum:")) {
                collectMachineCodes(tokens, trimmed.substring("enum:".length()));
                inMachineContext = trimmed.equals("enum:");
                contextIndent = indent;
                continue;
            }
            if (trimmed.startsWith("example:") || trimmed.startsWith("examples:") || trimmed.startsWith("value:")) {
                collectMachineCodes(tokens, trimmed.substring(trimmed.indexOf(':') + 1));
                inMachineContext = trimmed.endsWith(":");
                contextIndent = indent;
                continue;
            }
            if (trimmed.startsWith("operationId:")) {
                collectMachineCodes(tokens, trimmed.substring("operationId:".length()));
                continue;
            }
            if (inMachineContext) {
                collectMachineCodes(tokens, trimmed);
            }
        }
        return tokens;
    }

    private static void collectMachineCodes(List<String> tokens, String value) {
        Matcher matcher = MACHINE_CODE.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
    }
}
