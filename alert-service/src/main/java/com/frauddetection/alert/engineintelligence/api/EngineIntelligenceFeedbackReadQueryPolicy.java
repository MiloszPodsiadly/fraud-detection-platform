package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Component
public class EngineIntelligenceFeedbackReadQueryPolicy {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 50;

    private static final Set<String> ALLOWED_PARAMETERS = Set.of("limit");

    public int limit(MultiValueMap<String, String> parameters) {
        validateParameters(parameters);
        String value = value(parameters, "limit");
        if (!StringUtils.hasText(value)) {
            return DEFAULT_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw invalidLimit();
        }
        if (parsed < 1 || parsed > MAX_LIMIT) {
            throw invalidLimit();
        }
        return parsed;
    }

    private void validateParameters(MultiValueMap<String, String> parameters) {
        if (parameters == null) {
            return;
        }
        for (String name : parameters.keySet()) {
            if (!ALLOWED_PARAMETERS.contains(name)) {
                throw invalidRequest();
            }
        }
        for (String name : ALLOWED_PARAMETERS) {
            if (parameters.getOrDefault(name, List.of()).size() > 1) {
                throw invalidRequest();
            }
        }
    }

    private String value(MultiValueMap<String, String> parameters, String name) {
        if (parameters == null || !parameters.containsKey(name) || parameters.get(name).isEmpty()) {
            return null;
        }
        return parameters.getFirst(name);
    }

    private InvalidEngineIntelligenceFeedbackRequestException invalidLimit() {
        return new InvalidEngineIntelligenceFeedbackRequestException(List.of("limit: must be between 1 and 50"));
    }

    private InvalidEngineIntelligenceFeedbackRequestException invalidRequest() {
        return new InvalidEngineIntelligenceFeedbackRequestException(List.of("query: invalid parameters"));
    }
}
