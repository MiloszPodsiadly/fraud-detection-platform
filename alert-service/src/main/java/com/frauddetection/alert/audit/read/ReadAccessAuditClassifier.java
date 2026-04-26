package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Component
public class ReadAccessAuditClassifier {

    @SuppressWarnings("unchecked")
    public Optional<ReadAccessAuditTarget> classify(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return Optional.empty();
        }
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Map<String, String> variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        variables = variables == null ? Map.of() : variables;

        if ("/api/v1/alerts/{alertId}".equals(pattern)) {
            return Optional.of(target(ReadAccessEndpointCategory.ALERT_DETAIL, ReadAccessResourceType.ALERT, variables.get("alertId"), request));
        }
        if ("/api/v1/fraud-cases/{caseId}".equals(pattern)) {
            return Optional.of(target(ReadAccessEndpointCategory.FRAUD_CASE_DETAIL, ReadAccessResourceType.FRAUD_CASE, variables.get("caseId"), request));
        }
        if ("/api/v1/transactions/scored".equals(pattern)) {
            return Optional.of(target(ReadAccessEndpointCategory.SCORED_TRANSACTION_LIST, ReadAccessResourceType.SCORED_TRANSACTION, null, request));
        }
        if ("/governance/advisories".equals(pattern)) {
            return Optional.of(target(ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_LIST, ReadAccessResourceType.GOVERNANCE_ADVISORY_LIST, null, request));
        }
        if ("/governance/advisories/{eventId}".equals(pattern)) {
            return Optional.of(target(ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_DETAIL, ReadAccessResourceType.GOVERNANCE_ADVISORY, variables.get("eventId"), request));
        }
        if ("/governance/advisories/{eventId}/audit".equals(pattern)) {
            return Optional.of(target(ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_AUDIT_HISTORY, ReadAccessResourceType.GOVERNANCE_ADVISORY_AUDIT, variables.get("eventId"), request));
        }
        if ("/governance/advisories/analytics".equals(pattern)) {
            return Optional.of(target(ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_ANALYTICS, ReadAccessResourceType.GOVERNANCE_ADVISORY_ANALYTICS, null, request));
        }
        return Optional.empty();
    }

    private ReadAccessAuditTarget target(
            ReadAccessEndpointCategory category,
            ReadAccessResourceType resourceType,
            String resourceId,
            HttpServletRequest request
    ) {
        return new ReadAccessAuditTarget(
                category,
                resourceType,
                normalize(resourceId),
                queryHash(request),
                intParameter(request, "page"),
                intParameter(request, "size")
        );
    }

    private Integer intParameter(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String queryHash(HttpServletRequest request) {
        String canonicalQuery = canonicalQuery(request);
        if (canonicalQuery == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalQuery.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 16 && i < digest.length; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for read-access audit query hashing", exception);
        }
    }

    private String canonicalQuery(HttpServletRequest request) {
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap == null || parameterMap.isEmpty()) {
            return null;
        }
        TreeMap<String, String[]> sorted = new TreeMap<>();
        parameterMap.forEach((name, values) -> {
            if (name == null || name.isBlank() || values == null) {
                return;
            }
            String[] normalizedValues = Arrays.stream(values)
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .sorted()
                    .toArray(String[]::new);
            if (normalizedValues.length > 0) {
                sorted.put(name.trim(), normalizedValues);
            }
        });
        if (sorted.isEmpty()) {
            return null;
        }
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((name, values) -> Arrays.stream(values).forEach(value ->
                canonical.append(name).append('=').append(value).append('\n')
        ));
        return canonical.toString();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
