package com.frauddetection.alert.audit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record AuditEventBusinessSemantics(
        boolean compensated,
        String supersededByEventId,
        boolean businessEffective,
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
            return new AuditEventBusinessSemantics(false, null, false, document.resourceId());
        }
        if (abort != null) {
            return new AuditEventBusinessSemantics(true, abort.auditId(), false, null);
        }
        return new AuditEventBusinessSemantics(false, null, document.outcome() == AuditOutcome.SUCCESS, null);
    }

    private static boolean isExternalAnchorAbort(AuditEventDocument document) {
        return document.action() == AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED
                && document.resourceType() == AuditResourceType.AUDIT_EVENT
                && (document.outcome() == AuditOutcome.ABORTED_EXTERNAL_ANCHOR_REQUIRED
                || document.outcome() == AuditOutcome.FAILED);
    }
}
