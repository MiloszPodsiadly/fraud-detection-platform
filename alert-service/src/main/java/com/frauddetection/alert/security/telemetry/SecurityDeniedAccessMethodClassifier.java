package com.frauddetection.alert.security.telemetry;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class SecurityDeniedAccessMethodClassifier {

    public String classify(String method) {
        if (method == null || method.isBlank()) {
            return "OTHER";
        }
        return switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "GET", "POST", "PUT", "PATCH", "DELETE" -> method.trim().toUpperCase(Locale.ROOT);
            default -> "OTHER";
        };
    }
}
