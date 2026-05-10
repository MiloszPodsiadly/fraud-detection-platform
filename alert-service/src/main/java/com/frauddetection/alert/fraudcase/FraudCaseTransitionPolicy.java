package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class FraudCaseTransitionPolicy {

    private static final Map<FraudCaseStatus, Set<FraudCaseStatus>> ALLOWED_TRANSITIONS = allowedTransitions();

    public void validateCreate(List<String> linkedAlertIds, FraudCasePriority priority) {
        if (linkedAlertIds == null || linkedAlertIds.stream().noneMatch(StringUtils::hasText)) {
            throw new FraudCaseValidationException("Fraud case must reference at least one alert.");
        }
        if (priority == null) {
            throw new FraudCaseValidationException("Fraud case priority is required.");
        }
    }

    public void validateAssign(FraudCaseStatus currentStatus, String assignedInvestigatorId) {
        rejectClosed(currentStatus, "Closed case cannot be assigned.");
        if (!StringUtils.hasText(assignedInvestigatorId)) {
            throw new FraudCaseValidationException("Assigned investigator is required.");
        }
    }

    public void validateAddNote(FraudCaseStatus currentStatus, String body) {
        rejectClosed(currentStatus, "Closed case cannot receive notes.");
        if (!StringUtils.hasText(body)) {
            throw new FraudCaseValidationException("Note body is required.");
        }
    }

    public void validateAddDecision(FraudCaseStatus currentStatus, FraudCaseDecisionType decisionType, String summary) {
        rejectClosed(currentStatus, "Closed case cannot receive decisions.");
        if (decisionType == null) {
            throw new FraudCaseValidationException("Decision type is required.");
        }
        if (!StringUtils.hasText(summary)) {
            throw new FraudCaseValidationException("Decision summary is required.");
        }
    }

    public void validateTransition(FraudCaseStatus currentStatus, FraudCaseStatus targetStatus) {
        if (targetStatus == null) {
            throw new FraudCaseValidationException("Target status is required.");
        }
        if (currentStatus == null) {
            throw new FraudCaseConflictException("Current case status is unknown.");
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(targetStatus)) {
            throw new FraudCaseConflictException("Forbidden fraud case status transition: " + currentStatus + " -> " + targetStatus);
        }
    }

    public void validateClose(FraudCaseStatus currentStatus, String closureReason) {
        if (!StringUtils.hasText(closureReason)) {
            throw new FraudCaseValidationException("Closure reason is required.");
        }
        validateTransition(currentStatus, FraudCaseStatus.CLOSED);
    }

    public void validateReopen(FraudCaseStatus currentStatus, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new FraudCaseValidationException("Reopen reason is required.");
        }
        validateTransition(currentStatus, FraudCaseStatus.REOPENED);
    }

    private void rejectClosed(FraudCaseStatus status, String message) {
        if (status == FraudCaseStatus.CLOSED) {
            throw new FraudCaseConflictException(message);
        }
    }

    private static Map<FraudCaseStatus, Set<FraudCaseStatus>> allowedTransitions() {
        Map<FraudCaseStatus, Set<FraudCaseStatus>> transitions = new EnumMap<>(FraudCaseStatus.class);
        transitions.put(FraudCaseStatus.OPEN, EnumSet.of(FraudCaseStatus.IN_REVIEW, FraudCaseStatus.ESCALATED));
        transitions.put(FraudCaseStatus.IN_REVIEW, EnumSet.of(FraudCaseStatus.ESCALATED, FraudCaseStatus.RESOLVED));
        transitions.put(FraudCaseStatus.ESCALATED, EnumSet.of(FraudCaseStatus.IN_REVIEW, FraudCaseStatus.RESOLVED));
        transitions.put(FraudCaseStatus.RESOLVED, EnumSet.of(FraudCaseStatus.CLOSED));
        transitions.put(FraudCaseStatus.CLOSED, EnumSet.of(FraudCaseStatus.REOPENED));
        transitions.put(FraudCaseStatus.REOPENED, EnumSet.of(FraudCaseStatus.IN_REVIEW, FraudCaseStatus.ESCALATED));
        return Map.copyOf(transitions);
    }
}
