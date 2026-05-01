package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RegulatedMutationRecoveryService {

    private static final String POST_COMMIT_AUDIT_DEGRADED = "POST_COMMIT_AUDIT_DEGRADED";

    private final RegulatedMutationCommandRepository commandRepository;
    private final RegulatedMutationAuditPhaseService auditPhaseService;
    private final AuditDegradationService auditDegradationService;
    private final AlertServiceMetrics metrics;
    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final Duration stuckThreshold;

    public RegulatedMutationRecoveryService(
            RegulatedMutationCommandRepository commandRepository,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            @Value("${app.regulated-mutation.recovery.stuck-threshold:PT2M}") Duration stuckThreshold
    ) {
        this.commandRepository = commandRepository;
        this.auditPhaseService = auditPhaseService;
        this.auditDegradationService = auditDegradationService;
        this.metrics = metrics;
        this.recoveryStrategies = recoveryStrategies == null ? List.of() : List.copyOf(recoveryStrategies);
        this.stuckThreshold = stuckThreshold;
    }

    public List<RegulatedMutationRecoveryResult> recoverStuckCommands() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(stuckThreshold);
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
        List<RegulatedMutationRecoveryResult> results = commands.values().stream()
                .filter(command -> !activeLease(command, now))
                .map(this::recover)
                .toList();
        results.forEach(result -> metrics.recordRegulatedMutationRecoveryOutcome(result.outcome().name()));
        recordBacklogMetric();
        return results;
    }

    public RegulatedMutationRecoveryRunResponse recoverNow() {
        List<RegulatedMutationRecoveryResult> results = recoverStuckCommands();
        long recovered = count(results, RegulatedMutationRecoveryOutcome.RECOVERED);
        long stillPending = count(results, RegulatedMutationRecoveryOutcome.STILL_PENDING);
        long recoveryRequired = count(results, RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        long failed = count(results, RegulatedMutationRecoveryOutcome.FAILED_TERMINAL);
        return new RegulatedMutationRecoveryRunResponse(recovered, stillPending, recoveryRequired, failed, results.size());
    }

    public RegulatedMutationRecoveryBacklogResponse backlog() {
        Instant now = Instant.now();
        long recoveryRequired = commandRepository.countByExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        long expiredProcessing = commandRepository.countByExecutionStatusAndLeaseExpiresAtBefore(
                RegulatedMutationExecutionStatus.PROCESSING,
                now
        );
        long failedTerminal = commandRepository.countByExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        long repeatedFailures = commandRepository.countByExecutionStatusAndAttemptCountGreaterThanEqual(
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                3
        );
        Long oldestAge = commandRepository.findTopByExecutionStatusOrderByUpdatedAtAsc(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED)
                .map(command -> Math.max(0L, Duration.between(command.getUpdatedAt(), now).toSeconds()))
                .orElse(null);
        List<RegulatedMutationCommandDocument> recoveryRequiredCommands =
                commandRepository.findTop100ByExecutionStatusOrderByUpdatedAtAsc(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        List<RegulatedMutationCommandDocument> expiredCommands =
                commandRepository.findTop100ByExecutionStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
                        RegulatedMutationExecutionStatus.PROCESSING,
                        now
                );
        Map<String, Long> byState = new LinkedHashMap<>();
        Map<String, Long> byAction = new LinkedHashMap<>();
        java.util.stream.Stream.concat(recoveryRequiredCommands.stream(), expiredCommands.stream())
                .forEach(command -> {
                    byState.merge(command.getState() == null ? "UNKNOWN" : command.getState().name(), 1L, Long::sum);
                    byAction.merge(command.getAction() == null ? "UNKNOWN" : command.getAction(), 1L, Long::sum);
                });
        metrics.recordRegulatedMutationRecoveryBacklog(recoveryRequired, oldestAge, failedTerminal, repeatedFailures);
        return new RegulatedMutationRecoveryBacklogResponse(
                recoveryRequired,
                expiredProcessing,
                oldestAge,
                failedTerminal,
                repeatedFailures,
                byState,
                byAction
        );
    }

    public long recoveryRequiredCount() {
        return commandRepository.countByExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
    }

    public RegulatedMutationCommandInspectionResponse inspect(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "regulated mutation command not found");
        }
        return commandRepository.findByIdempotencyKey(idempotencyKey.trim())
                .map(RegulatedMutationCommandInspectionResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "regulated mutation command not found"));
    }

    RegulatedMutationRecoveryResult recover(RegulatedMutationCommandDocument command) {
        RegulatedMutationRecoveryOutcome outcome = switch (command.getState()) {
            case REQUESTED, AUDIT_ATTEMPTED -> stillPending(command);
            case BUSINESS_COMMITTING -> recoveryRequired(command);
            case BUSINESS_COMMITTED -> recoverBusinessCommitted(command);
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
            if (!reconstructSnapshot(command)) {
                return recoveryRequired(command);
            }
        }
        if (!command.isSuccessAuditRecorded()) {
            try {
                String auditId = auditPhaseService.findPhaseAuditId(command, RegulatedMutationAuditPhase.SUCCESS);
                if (auditId == null) {
                    auditId = auditPhaseService.recordPhase(
                            command,
                            AuditAction.valueOf(command.getAction()),
                            AuditResourceType.valueOf(command.getResourceType()),
                            AuditOutcome.SUCCESS,
                            null
                    );
                }
                command.setSuccessAuditId(auditId);
                command.setSuccessAuditRecorded(true);
            } catch (RuntimeException exception) {
                command.setState(RegulatedMutationState.COMMITTED_DEGRADED);
                command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
                command.setLastError("POST_COMMIT_AUDIT_DEGRADED");
                command.setUpdatedAt(Instant.now());
                commandRepository.save(command);
                auditDegradationService.recordPostCommitDegraded(
                        AuditAction.valueOf(command.getAction()),
                        AuditResourceType.valueOf(command.getResourceType()),
                        command.getResourceId(),
                        POST_COMMIT_AUDIT_DEGRADED,
                        command.getId()
                );
                metrics.recordPostCommitAuditDegraded(command.getAction());
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
            if (!reconstructSnapshot(command)) {
                return recoveryRequired(command);
            }
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

    private RegulatedMutationRecoveryOutcome recoverBusinessCommitted(RegulatedMutationCommandDocument command) {
        if (!reconstructSnapshot(command)) {
            return recoveryRequired(command);
        }
        command.setState(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);
        return recoverSuccessAuditPending(command);
    }

    private RegulatedMutationRecoveryOutcome failedTerminal(RegulatedMutationCommandDocument command) {
        command.setExecutionStatus(RegulatedMutationExecutionStatus.FAILED);
        command.setUpdatedAt(Instant.now());
        commandRepository.save(command);
        return RegulatedMutationRecoveryOutcome.FAILED_TERMINAL;
    }

    private boolean reconstructSnapshot(RegulatedMutationCommandDocument command) {
        Optional<RegulatedMutationRecoveryStrategy> strategy = recoveryStrategy(command);
        if (strategy.isEmpty()) {
            return false;
        }
        try {
            RecoveryValidationResult validation = strategy.get().validateBusinessState(command);
            if (!validation.valid()) {
                command.setLastError(validation.reasonCode());
                return false;
            }
            Optional<RegulatedMutationResponseSnapshot> snapshot = strategy.get().reconstructSnapshot(command);
            snapshot.ifPresent(command::setResponseSnapshot);
            snapshot.map(RegulatedMutationResponseSnapshot::decisionEventId).ifPresent(command::setOutboxEventId);
            return snapshot.isPresent();
        } catch (RuntimeException exception) {
            command.setLastError("RECOVERY_STRATEGY_FAILED");
            return false;
        }
    }

    private Optional<RegulatedMutationRecoveryStrategy> recoveryStrategy(RegulatedMutationCommandDocument command) {
        try {
            AuditAction action = AuditAction.valueOf(command.getAction());
            AuditResourceType resourceType = AuditResourceType.valueOf(command.getResourceType());
            return recoveryStrategies.stream()
                    .filter(strategy -> strategy.supports(action, resourceType))
                    .findFirst();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean activeLease(RegulatedMutationCommandDocument command, Instant now) {
        return command.getExecutionStatus() == RegulatedMutationExecutionStatus.PROCESSING
                && command.getLeaseExpiresAt() != null
                && command.getLeaseExpiresAt().isAfter(now);
    }

    private long count(List<RegulatedMutationRecoveryResult> results, RegulatedMutationRecoveryOutcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }

    private void recordBacklogMetric() {
        backlog();
    }
}
