package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditExternalAnchorStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuditEventPublicationStatusLookup {

    private final AuditAnchorRepository anchorRepository;
    private final ExternalAuditAnchorPublicationStatusRepository statusRepository;

    public AuditEventPublicationStatusLookup(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorPublicationStatusRepository statusRepository
    ) {
        this.anchorRepository = anchorRepository;
        this.statusRepository = statusRepository;
    }

    public Map<String, AuditExternalAnchorStatus> statusesByAuditEventId(List<AuditEventDocument> documents) throws DataAccessException {
        return evidenceStatusesByAuditEventId(documents).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().externalAnchorStatus()));
    }

    public Map<String, AuditEventExternalEvidenceStatus> evidenceStatusesByAuditEventId(List<AuditEventDocument> documents) throws DataAccessException {
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }
        Set<Long> positions = documents.stream()
                .map(AuditEventDocument::chainPosition)
                .filter(position -> position != null && position > 0)
                .collect(Collectors.toSet());
        if (positions.isEmpty()) {
            return Map.of();
        }
        String partitionKey = documents.stream()
                .map(AuditEventDocument::partitionKey)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("source_service:alert-service");
        List<AuditAnchorDocument> anchors = anchorRepository.findByPartitionKeyAndChainPositionIn(partitionKey, positions);
        Map<Long, AuditAnchorDocument> anchorsByPosition = anchors.stream()
                .collect(Collectors.toMap(AuditAnchorDocument::chainPosition, Function.identity(), (left, right) -> left));
        List<String> localAnchorIds = anchors.stream().map(AuditAnchorDocument::anchorId).toList();
        Map<String, ExternalAuditAnchorPublicationStatusDocument> statusesByAnchorId = statusRepository.findByLocalAnchorIds(localAnchorIds)
                .stream()
                .collect(Collectors.toMap(ExternalAuditAnchorPublicationStatusDocument::localAnchorId, Function.identity(), (left, right) -> left));
        return documents.stream()
                .filter(document -> document.chainPosition() != null)
                .collect(Collectors.toMap(
                        AuditEventDocument::auditId,
                        document -> {
                            AuditAnchorDocument anchor = anchorsByPosition.get(document.chainPosition());
                            if (anchor == null) {
                                return new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.UNKNOWN, null);
                            }
                            ExternalAuditAnchorPublicationStatusDocument status = statusesByAnchorId.get(anchor.anchorId());
                            return status == null
                                    ? new AuditEventExternalEvidenceStatus(AuditExternalAnchorStatus.UNKNOWN, null)
                                    : new AuditEventExternalEvidenceStatus(toExternalAnchorStatus(status.externalPublicationStatus()), status.signatureStatus());
                        },
                        (left, right) -> left
                ));
    }

    private AuditExternalAnchorStatus toExternalAnchorStatus(String status) {
        if (ExternalAuditAnchor.STATUS_PUBLISHED.equals(status)) {
            return AuditExternalAnchorStatus.PUBLISHED;
        }
        if (ExternalAuditAnchor.STATUS_UNVERIFIED.equals(status)) {
            return AuditExternalAnchorStatus.UNVERIFIED;
        }
        if (ExternalAuditAnchor.STATUS_MISSING.equals(status)) {
            return AuditExternalAnchorStatus.MISSING;
        }
        if (ExternalAuditAnchor.STATUS_CONFLICT.equals(status) || ExternalAuditAnchor.STATUS_INVALID.equals(status)) {
            return AuditExternalAnchorStatus.CONFLICT;
        }
        if (ExternalAuditAnchor.STATUS_LOCAL_STATUS_UNVERIFIED.equals(status)) {
            return AuditExternalAnchorStatus.LOCAL_STATUS_UNVERIFIED;
        }
        if (ExternalAuditAnchor.STATUS_FAILED.equals(status)
                || ExternalAuditAnchor.STATUS_LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED.equals(status)) {
            return AuditExternalAnchorStatus.FAILED;
        }
        return AuditExternalAnchorStatus.UNKNOWN;
    }
}
