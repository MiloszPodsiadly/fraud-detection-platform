package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCaseTransitionPolicyTest {

    private final FraudCaseTransitionPolicy policy = new FraudCaseTransitionPolicy();

    @Test
    void shouldAllowConfiguredLifecycleTransitions() {
        Stream.of(
                transition(FraudCaseStatus.OPEN, FraudCaseStatus.IN_REVIEW),
                transition(FraudCaseStatus.OPEN, FraudCaseStatus.ESCALATED),
                transition(FraudCaseStatus.IN_REVIEW, FraudCaseStatus.ESCALATED),
                transition(FraudCaseStatus.IN_REVIEW, FraudCaseStatus.RESOLVED),
                transition(FraudCaseStatus.ESCALATED, FraudCaseStatus.IN_REVIEW),
                transition(FraudCaseStatus.ESCALATED, FraudCaseStatus.RESOLVED),
                transition(FraudCaseStatus.RESOLVED, FraudCaseStatus.CLOSED),
                transition(FraudCaseStatus.CLOSED, FraudCaseStatus.REOPENED),
                transition(FraudCaseStatus.REOPENED, FraudCaseStatus.IN_REVIEW),
                transition(FraudCaseStatus.REOPENED, FraudCaseStatus.ESCALATED)
        ).forEach(transition -> assertThatNoException()
                .isThrownBy(() -> policy.validateTransition(transition.from(), transition.to())));
    }

    @Test
    void shouldRejectForbiddenLifecycleTransitions() {
        assertThatThrownBy(() -> policy.validateTransition(FraudCaseStatus.CLOSED, FraudCaseStatus.OPEN))
                .isInstanceOf(FraudCaseConflictException.class);
        assertThatThrownBy(() -> policy.validateTransition(FraudCaseStatus.RESOLVED, FraudCaseStatus.OPEN))
                .isInstanceOf(FraudCaseConflictException.class);
        assertThatThrownBy(() -> policy.validateTransition(FraudCaseStatus.CLOSED, FraudCaseStatus.RESOLVED))
                .isInstanceOf(FraudCaseConflictException.class);
        assertThatThrownBy(() -> policy.validateTransition(FraudCaseStatus.OPEN, null))
                .isInstanceOf(FraudCaseValidationException.class);
    }

    @Test
    void shouldBlockClosedCaseModificationUnlessReopenIsExplicit() {
        assertThatThrownBy(() -> policy.validateAssign(FraudCaseStatus.CLOSED, "investigator-1"))
                .isInstanceOf(FraudCaseConflictException.class);
        assertThatThrownBy(() -> policy.validateAddNote(FraudCaseStatus.CLOSED, "note"))
                .isInstanceOf(FraudCaseConflictException.class);
        assertThatThrownBy(() -> policy.validateAddDecision(FraudCaseStatus.CLOSED, FraudCaseDecisionType.NO_ACTION, "summary"))
                .isInstanceOf(FraudCaseConflictException.class);
    }

    @Test
    void shouldValidateCreateCloseAndReopenInputs() {
        assertThatNoException().isThrownBy(() -> policy.validateCreate(List.of("alert-1"), FraudCasePriority.HIGH));
        assertThatThrownBy(() -> policy.validateCreate(List.of(), FraudCasePriority.HIGH))
                .isInstanceOf(FraudCaseValidationException.class);
        assertThatThrownBy(() -> policy.validateClose(FraudCaseStatus.RESOLVED, " "))
                .isInstanceOf(FraudCaseValidationException.class);
        assertThatThrownBy(() -> policy.validateReopen(FraudCaseStatus.CLOSED, " "))
                .isInstanceOf(FraudCaseValidationException.class);
    }

    private Transition transition(FraudCaseStatus from, FraudCaseStatus to) {
        return new Transition(from, to);
    }

    private record Transition(FraudCaseStatus from, FraudCaseStatus to) {
    }
}
