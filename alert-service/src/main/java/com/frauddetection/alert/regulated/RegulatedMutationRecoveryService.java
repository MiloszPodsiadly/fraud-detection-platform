package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
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

    private final RegulatedMutationCommandRepository commandRepository;
    private final AlertServiceMetrics metrics;
    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final Duration stuckThreshold;

    public RegulatedMutationRecoveryService(
            RegulatedMutationCommandRepository commandRepository,
            AlertServiceMetrics metrics,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            @Value("${app.regulated-mutation.recovery.stuck-threshold:PT2M}") Duration stuckThreshold
    ) {
        this.commandRepository = commandRepository;
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
                                RegulatedMutationState.EVIDENCE_PREPARING,
                                RegulatedMutationState.EVIDENCE_PREPARED,
                                RegulatedMutationState.FINALIZING,
                                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
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

    public RegulatedMutationCommandInspectionResponse inspectByCommandId(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "regulated mutation command not found");
        }
        return commandRepository.findById(commandId.trim())
                .map(RegulatedMutationCommandInspectionResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "regulated mutation command not found"));
    }

    public RegulatedMutationCommandInspectionResponse inspectByIdempotencyHash(String idempotencyHash) {
        if (idempotencyHash == null || !idempotencyHash.matches("^[a-fA-F0-9]{64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid idempotency hash");
        }
        return commandRepository.findByIdempotencyKeyHash(idempotencyHash.toLowerCase(java.util.Locale.ROOT))
                .map(RegulatedMutationCommandInspectionResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "regulated mutation command not found"));
    }

    public long staleProcessingLeaseCount() {
        return commandRepository.countByExecutionStatusAndLeaseExpiresAtBefore(
                RegulatedMutationExecutionStatus.PROCESSING,
                Instant.now()
        );
    }

    public long finalizeRecoveryRequiredCount() {
        return commandRepository.countByState(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    public long evidenceConfirmationPendingCount() {
        return commandRepository.countByStateIn(List.of(
                RegulatedMutationState.FINALIZED_VISIBLE,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        ));
    }

    public long evidenceConfirmationRecoveryRequiredCount() {
        return commandRepository.countByState(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    public long repeatedRecoveryFailureCount() {
        return commandRepository.countByExecutionStatusAndAttemptCountGreaterThanEqual(
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                3
        );
    }

    public Long oldestRecoveryRequiredAgeSeconds() {
        Instant now = Instant.now();
        return commandRepository.findTopByExecutionStatusOrderByUpdatedAtAsc(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED)
                .map(command -> Math.max(0L, Duration.between(command.getUpdatedAt(), now).toSeconds()))
                .orElse(null);
    }

    RegulatedMutationRecoveryResult recover(RegulatedMutationCommandDocument command) {
        requireCurrentSupportedCommand(command);
        RegulatedMutationRecoveryOutcome outcome = switch (command.getState()) {
            case REQUESTED, EVIDENCE_PREPARING, EVIDENCE_PREPARED -> stillPending(command);
            case FINALIZING, FINALIZE_RECOVERY_REQUIRED -> recoveryRequired(command);
            case FINALIZED_VISIBLE, FINALIZED_EVIDENCE_PENDING_EXTERNAL, FINALIZED_EVIDENCE_CONFIRMED ->
                    completeIfSnapshotExists(command);
            case FAILED, REJECTED_EVIDENCE_UNAVAILABLE, FAILED_BUSINESS_VALIDATION -> failedTerminal(command);
        };
        return new RegulatedMutationRecoveryResult(
                command.getIdempotencyKey(),
                command.getState(),
                command.getExecutionStatus(),
                outcome
        );
    }

    private void requireCurrentSupportedCommand(RegulatedMutationCommandDocument command) {
        if (command.getMutationModelVersion() != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            throw new IllegalStateException("Unsupported persisted regulated mutation model version.");
        }
        try {
            RegulatedMutationDefinitions.requireSupported(
                    AuditAction.valueOf(command.getAction()),
                    AuditResourceType.valueOf(command.getResourceType())
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unsupported persisted regulated mutation operation.", exception);
        }
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

    private RegulatedMutationRecoveryOutcome completeIfSnapshotExists(RegulatedMutationCommandDocument command) {
        if (command.getResponseSnapshot() == null) {
            if (!reconstructSnapshot(command)) {
                return recoveryRequired(command);
            }
        }
        command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        command.setLeaseOwner(null);
        command.setLeaseExpiresAt(null);
        if (command.getState() == RegulatedMutationState.FINALIZED_VISIBLE) {
            command.setState(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        }
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
