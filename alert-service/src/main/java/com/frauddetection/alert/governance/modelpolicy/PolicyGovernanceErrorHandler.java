package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.http.HttpStatus;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = PolicyGovernanceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PolicyGovernanceErrorHandler {
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, String> denied(AccessDeniedException exception) {
        return Map.of("error", "POLICY_GOVERNANCE_FORBIDDEN", "message", exception.getMessage());
    }

    @ExceptionHandler(PolicyGovernanceConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(PolicyGovernanceConflictException exception) {
        return Map.of("error", "POLICY_GOVERNANCE_CONFLICT", "message", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(IllegalArgumentException exception) {
        return Map.of("error", "INVALID_POLICY_GOVERNANCE_REQUEST", "message", exception.getMessage());
    }
}
