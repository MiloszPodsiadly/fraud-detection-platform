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
import java.util.function.Consumer;

import org.springframework.data.mongodb.core.query.Update;

@Service
public class RegulatedMutationRecoveryService {

    private final RegulatedMutationCommandRepository commandRepository;
    private final AlertServiceMetrics metrics;
    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final RegulatedMutationFencedCommandWriter fencedCommandWriter;
    private final Duration stuckThreshold;

    public RegulatedMutationRecoveryService(
            RegulatedMutationCommandRepository commandRepository,
            AlertServiceMetrics metrics,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            RegulatedMutationFencedCommandWriter fencedCommandWriter,
            @Value("${app.regulated-mutation.recovery.stuck-threshold:PT2M}") Duration stuckThreshold
    ) {
        this.commandRepository = commandRepository;
        this.metrics = metrics;
        this.recoveryStrategies = recoveryStrategies == null ? List.of() : List.copyOf(recoveryStrategies);
        this.fencedCommandWriter = fencedCommandWriter;
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
                .map(this::recoverWithoutRacingActiveWork)
                .flatMap(Optional::stream)
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

    private Optional<RegulatedMutationRecoveryResult> recoverWithoutRacingActiveWork(
            RegulatedMutationCommandDocument command
    ) {
        try {
            return Optional.of(recover(command));
        } catch (RegulatedMutationRecoveryWriteConflictException conflict) {
            return Optional.empty();
        }
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
        transition(command, command.getState(), RegulatedMutationExecutionStatus.NEW, null, update -> {
        });
        return RegulatedMutationRecoveryOutcome.STILL_PENDING;
    }

    private RegulatedMutationRecoveryOutcome completeIfSnapshotExists(RegulatedMutationCommandDocument command) {
        if (command.getResponseSnapshot() == null) {
            if (!reconstructSnapshot(command)) {
                return recoveryRequired(command);
            }
        }
        RegulatedMutationState targetState = command.getState() == RegulatedMutationState.FINALIZED_VISIBLE
                ? RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                : command.getState();
        transition(
                command,
                targetState,
                RegulatedMutationExecutionStatus.COMPLETED,
                null,
                update -> update
                        .set("response_snapshot", command.getResponseSnapshot())
                        .set("outbox_event_id", command.getOutboxEventId())
        );
        return RegulatedMutationRecoveryOutcome.RECOVERED;
    }

    private RegulatedMutationRecoveryOutcome recoveryRequired(RegulatedMutationCommandDocument command) {
        transition(
                command,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                update -> {
                }
        );
        return RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED;
    }

    private RegulatedMutationRecoveryOutcome failedTerminal(RegulatedMutationCommandDocument command) {
        transition(command, command.getState(), RegulatedMutationExecutionStatus.FAILED, command.getLastError(), update -> {
        });
        return RegulatedMutationRecoveryOutcome.FAILED_TERMINAL;
    }

    private void transition(
            RegulatedMutationCommandDocument command,
            RegulatedMutationState targetState,
            RegulatedMutationExecutionStatus targetExecutionStatus,
            String lastError,
            Consumer<Update> additionalUpdates
    ) {
        fencedCommandWriter.recoveryTransition(
                command,
                targetState,
                targetExecutionStatus,
                lastError,
                additionalUpdates
        );
        command.setState(targetState);
        command.setExecutionStatus(targetExecutionStatus);
        command.setLeaseOwner(null);
        command.setLeaseExpiresAt(null);
        command.setLastError(lastError);
        command.setUpdatedAt(Instant.now());
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
