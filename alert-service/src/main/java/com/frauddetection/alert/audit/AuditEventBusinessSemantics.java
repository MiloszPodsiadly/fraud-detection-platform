package com.frauddetection.alert.audit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record AuditEventBusinessSemantics(
        boolean compensated,
        String supersededByEventId,
        boolean businessEffective,
        BusinessEffectiveStatus businessEffectiveStatus,
        AuditEvidenceStatus auditEvidenceStatus,
        AuditExternalAnchorStatus externalAnchorStatus,
        CompensationType compensationType,
        String relatedEventId
) {
    public static Map<String, AuditEventBusinessSemantics> index(List<AuditEventDocument> documents) {
        return index(documents, List.of());
    }

    public static Map<String, AuditEventBusinessSemantics> index(
            List<AuditEventDocument> documents,
            List<AuditEventDocument> compensations
    ) {
        Map<String, AuditEventDocument> abortByRelatedEventId = java.util.stream.Stream.concat(
                        documents.stream(),
                        compensations.stream()
                )
                .filter(AuditEventBusinessSemantics::isExternalAnchorAbort)
                .filter(document -> document.resourceId() != null)
                .collect(Collectors.toMap(
                        AuditEventDocument::resourceId,
                        Function.identity(),
                        (left, right) -> left
                ));
        return documents.stream()
                .collect(Collectors.toMap(
                        AuditEventDocument::auditId,
                        document -> from(document, abortByRelatedEventId),
                        (left, right) -> left
                ));
    }

    public static AuditEventBusinessSemantics from(AuditEventDocument document) {
        return from(document, Map.of());
    }

    private static AuditEventBusinessSemantics from(
            AuditEventDocument document,
            Map<String, AuditEventDocument> abortByRelatedEventId
    ) {
        AuditEventDocument abort = abortByRelatedEventId.get(document.auditId());
        if (isExternalAnchorAbort(document)) {
            return new AuditEventBusinessSemantics(
                    false,
                    null,
                    false,
                    BusinessEffectiveStatus.FALSE,
                    AuditEvidenceStatus.ANCHOR_REQUIRED_FAILED,
                    AuditExternalAnchorStatus.FAILED,
                    CompensationType.EXTERNAL_ANCHOR_FAILURE,
                    document.resourceId()
            );
        }
        if (abort != null) {
            BusinessEffectiveStatus businessStatus = businessEffectiveStatus(document);
            return new AuditEventBusinessSemantics(
                    true,
                    abort.auditId(),
                    businessStatus == BusinessEffectiveStatus.TRUE,
                    businessStatus,
                    AuditEvidenceStatus.ANCHOR_REQUIRED_FAILED,
                    AuditExternalAnchorStatus.FAILED,
                    CompensationType.EXTERNAL_ANCHOR_FAILURE,
                    null
            );
        }
        BusinessEffectiveStatus businessStatus = businessEffectiveStatus(document);
        return new AuditEventBusinessSemantics(
                false,
                null,
                businessStatus == BusinessEffectiveStatus.TRUE,
                businessStatus,
                auditEvidenceStatus(document),
                AuditExternalAnchorStatus.LOCAL_STATUS_UNVERIFIED,
                compensationType(document),
                null
        );
    }

    private static BusinessEffectiveStatus businessEffectiveStatus(AuditEventDocument document) {
        if (document.outcome() == AuditOutcome.SUCCESS) {
            return BusinessEffectiveStatus.TRUE;
        }
        if (document.outcome() == AuditOutcome.ATTEMPTED
                || document.outcome() == AuditOutcome.FAILED
                || document.outcome() == AuditOutcome.REJECTED
                || document.outcome() == AuditOutcome.ABORTED_EXTERNAL_ANCHOR_REQUIRED) {
            return BusinessEffectiveStatus.FALSE;
        }
        return BusinessEffectiveStatus.UNKNOWN;
    }

    private static AuditEvidenceStatus auditEvidenceStatus(AuditEventDocument document) {
        return AuditEvidenceStatus.LOCAL_ONLY;
    }

    private static CompensationType compensationType(AuditEventDocument document) {
        return document.outcome() == AuditOutcome.FAILED
                ? CompensationType.BUSINESS_WRITE_FAILURE
                : CompensationType.UNKNOWN;
    }

    private static boolean isExternalAnchorAbort(AuditEventDocument document) {
        return document.action() == AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED
                && document.resourceType() == AuditResourceType.AUDIT_EVENT
                && (document.outcome() == AuditOutcome.ABORTED_EXTERNAL_ANCHOR_REQUIRED
                || document.outcome() == AuditOutcome.FAILED);
    }
}
