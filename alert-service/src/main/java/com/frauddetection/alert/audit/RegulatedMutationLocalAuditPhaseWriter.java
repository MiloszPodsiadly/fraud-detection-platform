package com.frauddetection.alert.audit;

import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class RegulatedMutationLocalAuditPhaseWriter {

    private final AuditEventRepository auditEventRepository;
    private final AuditAnchorRepository auditAnchorRepository;
    private final AuditChainLockRepository lockRepository;

    public RegulatedMutationLocalAuditPhaseWriter(
            AuditEventRepository auditEventRepository,
            AuditAnchorRepository auditAnchorRepository,
            AuditChainLockRepository lockRepository
    ) {
        this.auditEventRepository = auditEventRepository;
        this.auditAnchorRepository = auditAnchorRepository;
        this.lockRepository = lockRepository;
    }

    public String recordSuccessPhase(
            RegulatedMutationCommandDocument command,
            AuditAction action,
            AuditResourceType resourceType
    ) {
        String phaseKey = phaseKey(command, "SUCCESS");
        return auditEventRepository.findByRequestId(phaseKey)
                .map(AuditEventDocument::auditId)
                .orElseGet(() -> appendLocalSuccess(command, action, resourceType, phaseKey));
    }

    private String appendLocalSuccess(
            RegulatedMutationCommandDocument command,
            AuditAction action,
            AuditResourceType resourceType,
            String phaseKey
    ) {
        String lockOwner = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        try {
            lockRepository.acquire(AuditEventDocument.PARTITION_KEY, lockOwner);
            lockAcquired = true;
            AuditEventDocument previous = auditEventRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)
                    .orElse(null);
            String previousHash = previous == null ? null : previous.eventHash();
            long chainPosition = nextChainPosition(previous);
            AuditEvent event = new AuditEvent(
                    new AuditActor(command.getActorId(), Set.of(), Set.of()),
                    action,
                    resourceType,
                    command.getResourceId(),
                    Instant.now(),
                    command.getCorrelationId(),
                    phaseKey,
                    AuditOutcome.SUCCESS,
                    AuditFailureCategory.NONE,
                    null,
                    new AuditEventMetadataSummary(
                            command.getCorrelationId(),
                            phaseKey,
                            "alert-service",
                            "1.0",
                            null,
                            null,
                            null,
                            null,
                            null
                    )
            );
            AuditEventDocument document = auditEventRepository.insert(AuditEventDocument.from(
                    UUID.randomUUID().toString(),
                    event,
                    previousHash,
                    chainPosition
            ));
            auditAnchorRepository.insert(AuditAnchorDocument.from(UUID.randomUUID().toString(), document));
            return document.auditId();
        } catch (DuplicateKeyException duplicate) {
            return auditEventRepository.findByRequestId(phaseKey)
                    .map(AuditEventDocument::auditId)
                    .orElseThrow(() -> duplicate);
        } catch (DataAccessException exception) {
            throw new AuditPersistenceUnavailableException();
        } finally {
            if (lockAcquired) {
                try {
                    lockRepository.release(AuditEventDocument.PARTITION_KEY, lockOwner);
                } catch (DataAccessException ignored) {
                    // The surrounding Mongo transaction determines whether the local audit write commits.
                }
            }
        }
    }

    private long nextChainPosition(AuditEventDocument previous) {
        if (previous == null) {
            return 1L;
        }
        if (previous.chainPosition() != null && previous.chainPosition() > 0) {
            return previous.chainPosition() + 1L;
        }
        return auditEventRepository.countByPartitionKey(AuditEventDocument.PARTITION_KEY) + 1L;
    }

    private String phaseKey(RegulatedMutationCommandDocument command, String phase) {
        String commandId = command.getId();
        if (commandId == null || commandId.isBlank()) {
            commandId = command.getIdempotencyKey();
        }
        return commandId + ":" + phase;
    }
}
