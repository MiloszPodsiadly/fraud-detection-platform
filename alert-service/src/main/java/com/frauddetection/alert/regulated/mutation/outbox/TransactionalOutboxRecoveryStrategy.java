package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.OutboxRecordResponse;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.regulated.RecoveryValidationResult;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryStrategy;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TransactionalOutboxRecoveryStrategy implements RegulatedMutationRecoveryStrategy {

    private static final String BUSINESS_STATE_NOT_RECONSTRUCTABLE = "BUSINESS_STATE_NOT_RECONSTRUCTABLE";

    private final TransactionalOutboxRecordRepository repository;

    public TransactionalOutboxRecoveryStrategy(TransactionalOutboxRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean supports(AuditAction action, AuditResourceType resourceType) {
        return action == AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION
                && resourceType == AuditResourceType.DECISION_OUTBOX;
    }

    @Override
    public Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command) {
        return repository.findById(command.getResourceId())
                .map(OutboxRecordResponse::from)
                .map(RegulatedMutationResponseSnapshot::from);
    }

    @Override
    public RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command) {
        return repository.findById(command.getResourceId()).isPresent()
                ? RecoveryValidationResult.accepted()
                : RecoveryValidationResult.recoveryRequired(BUSINESS_STATE_NOT_RECONSTRUCTABLE);
    }
}
