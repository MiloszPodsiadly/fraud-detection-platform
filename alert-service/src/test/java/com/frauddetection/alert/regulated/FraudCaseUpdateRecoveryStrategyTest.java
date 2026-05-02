package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudCaseUpdateRecoveryStrategyTest {

    @Test
    void shouldAcceptBusinessStateMatchingStoredIntent() {
        FraudCaseRepository repository = mock(FraudCaseRepository.class);
        FraudCaseUpdateRecoveryStrategy strategy = new FraudCaseUpdateRecoveryStrategy(repository);
        FraudCaseDocument document = fraudCase(FraudCaseStatus.CONFIRMED_FRAUD);
        RegulatedMutationCommandDocument command = command(document);

        when(repository.findById("case-1")).thenReturn(Optional.of(document));

        assertThat(strategy.supports(AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE)).isTrue();
        assertThat(strategy.validateBusinessState(command).valid()).isTrue();
        assertThat(strategy.reconstructSnapshot(command)).isPresent();
    }

    @Test
    void shouldRequireRecoveryWhenBusinessStateDoesNotMatchStoredIntent() {
        FraudCaseRepository repository = mock(FraudCaseRepository.class);
        FraudCaseUpdateRecoveryStrategy strategy = new FraudCaseUpdateRecoveryStrategy(repository);
        FraudCaseDocument document = fraudCase(FraudCaseStatus.OPEN);
        RegulatedMutationCommandDocument command = command(fraudCase(FraudCaseStatus.CONFIRMED_FRAUD));

        when(repository.findById("case-1")).thenReturn(Optional.of(document));

        RecoveryValidationResult result = strategy.validateBusinessState(command);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("BUSINESS_STATE_INTENT_MISMATCH");
    }

    private RegulatedMutationCommandDocument command(FraudCaseDocument document) {
        UpdateFraudCaseRequest request = new UpdateFraudCaseRequest(
                document.getStatus(),
                "principal-9",
                document.getDecisionReason(),
                document.getDecisionTags()
        );
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.fraudCaseUpdate(
                document.getCaseId(),
                "principal-9",
                document.getStatus(),
                "principal-9",
                document.getDecisionReason(),
                document.getDecisionTags(),
                "status=" + RegulatedMutationIntentHasher.canonicalValue(request.status())
                        + "|analystId=principal-9"
                        + "|decisionReason=" + RegulatedMutationIntentHasher.canonicalValue(request.decisionReason())
                        + "|tags=" + RegulatedMutationIntentHasher.canonicalValue(request.tags())
        );
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setResourceId(document.getCaseId());
        command.setAction(AuditAction.UPDATE_FRAUD_CASE.name());
        command.setResourceType(AuditResourceType.FRAUD_CASE.name());
        command.setIntentHash(intent.intentHash());
        command.setIntentResourceId(intent.resourceId());
        command.setIntentAction(intent.action());
        command.setIntentActorId("principal-9");
        command.setIntentStatus(intent.status());
        command.setIntentAssigneeHash(intent.assigneeHash());
        command.setIntentNotesHash(intent.notesHash());
        command.setIntentTagsHash(intent.tagsHash());
        command.setIntentPayloadHash(intent.payloadHash());
        return command;
    }

    private FraudCaseDocument fraudCase(FraudCaseStatus status) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setStatus(status);
        document.setAnalystId("principal-9");
        document.setDecisionReason("Confirmed after review");
        document.setDecisionTags(List.of("manual-review"));
        document.setDecidedAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return document;
    }
}
