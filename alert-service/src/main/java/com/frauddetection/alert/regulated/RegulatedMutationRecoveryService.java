package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RegulatedMutationRecoveryService {

    private final RegulatedMutationCommandRepository commandRepository;
    private final AuditService auditService;
    private final Duration stuckThreshold;

    public RegulatedMutationRecoveryService(
            RegulatedMutationCommandRepository commandRepository,
            AuditService auditService,
            @Value("${app.regulated-mutation.recovery.stuck-threshold:PT2M}") Duration stuckThreshold
    ) {
        this.commandRepository = commandRepository;
        this.auditService = auditService;
        this.stuckThreshold = stuckThreshold;
    }

    public List<RegulatedMutationRecoveryResult> recoverStuckCommands() {
        Instant cutoff = Instant.now().minus(stuckThreshold);
        Map<String, RegulatedMutationCommandDocument> commands = new LinkedHashMap<>();
        commandRepository.findTop100ByExecutionStatusInAndUpdatedAtBefore(
                        Set.of(
                                RegulatedMutationExecutionStatus.NEW,
                                RegulatedMutationExecutionStatus.PROCESSING,
                                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
                        ),
                        cutoff
                )
                .forEach(command -> commands.put(command.getIdempotencyKey(), command));
        commandRepository.findTop100ByStateInAndUpdatedAtBefore(
                        Set.of(
                                RegulatedMutationState.REQUESTED,
                                RegulatedMutationState.AUDIT_ATTEMPTED,
                                RegulatedMutationState.BUSINESS_COMMITTING,
                                RegulatedMutationState.BUSINESS_COMMITTED,
                                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                                RegulatedMutationState.COMMITTED_DEGRADED,
                                RegulatedMutationState.EVIDENCE_PENDING
                        ),
                        cutoff
                )
                .forEach(command -> commands.putIfAbsent(command.getIdempotencyKey(), command));
        return commands.values().stream()
                .map(this::recover)
                .toList();
    }

    RegulatedMutationRecoveryResult recover(RegulatedMutationCommandDocument command) {
        RegulatedMutationRecoveryOutcome outcome = switch (command.getState()) {
            case REQUESTED, AUDIT_ATTEMPTED -> stillPending(command);
            case BUSINESS_COMMITTING, BUSINESS_COMMITTED -> recoveryRequired(command);
            case SUCCESS_AUDIT_PENDING -> recoverSuccessAuditPending(command);
            case SUCCESS_AUDIT_RECORDED, EVIDENCE_PENDING, EVIDENCE_CONFIRMED, COMMITTED, COMMITTED_DEGRADED ->
                    completeIfSnapshotExists(command);
            case REJECTED, FAILED -> failedTerminal(command);
        };
        return new RegulatedMutationRecoveryResult(
                command.getIdempotencyKey(),
                command.getState(),
                command.getExecutionStatus(),
                outcome
        );
    }

    private RegulatedMutationRecoveryOutcome stillPending(RegulatedMutationCommandDocument command) {
        command.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        command.setLeaseOwner(null);
        command.setLeaseExpiresAt(null);
        command.setLastError(null);
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);
        return RegulatedMutationRecoveryOutcome.STILL_PENDING;
    }

    private RegulatedMutationRecoveryOutcome recoverSuccessAuditPending(RegulatedMutationCommandDocument command) {
        if (command.getResponseSnapshot() == null) {
            return recoveryRequired(command);
        }
        if (!command.isSuccessAuditRecorded()) {
            try {
                auditService.audit(
                        AuditAction.valueOf(command.getAction()),
                        AuditResourceType.valueOf(command.getResourceType()),
                        command.getResourceId(),
                        command.getCorrelationId(),
                        command.getActorId(),
                        AuditOutcome.SUCCESS,
                        null
                );
                command.setSuccessAuditRecorded(true);
            } catch (RuntimeException exception) {
                command.setState(RegulatedMutationState.COMMITTED_DEGRADED);
                command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
                command.setLastError("POST_COMMIT_AUDIT_DEGRADED");
                command.setUpdatedAt(Instant.now());
                commandRepository.save(command);
                return RegulatedMutationRecoveryOutcome.RECOVERED;
            }
        }
        command.setState(RegulatedMutationState.EVIDENCE_PENDING);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        command.setLastError(null);
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);
        return RegulatedMutationRecoveryOutcome.RECOVERED;
    }

    private RegulatedMutationRecoveryOutcome completeIfSnapshotExists(RegulatedMutationCommandDocument command) {
        if (command.getResponseSnapshot() == null) {
            return recoveryRequired(command);
        }
        command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        command.setLeaseOwner(null);
        command.setLeaseExpiresAt(null);
        command.setLastError(null);
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);
        return RegulatedMutationRecoveryOutcome.RECOVERED;
    }

    private RegulatedMutationRecoveryOutcome recoveryRequired(RegulatedMutationCommandDocument command) {
        command.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        command.setLastError("RECOVERY_REQUIRED");
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);
        return RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED;
    }

    private RegulatedMutationRecoveryOutcome failedTerminal(RegulatedMutationCommandDocument command) {
        command.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);
        return RegulatedMutationRecoveryOutcome.FAILED_TERMINAL;
    }
}
