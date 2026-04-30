package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditExternalAnchorStatus;
import com.frauddetection.alert.audit.AuditFailureCategory;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventPublicationStatusLookupTest {

    @Test
    void shouldUsePublicationStatusRepositoryAsExternalAnchorSourceOfTruth() {
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorPublicationStatusRepository statusRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditEventPublicationStatusLookup lookup = new AuditEventPublicationStatusLookup(anchorRepository, statusRepository);
        AuditEventDocument first = event("audit-1", 1L);
        AuditEventDocument second = event("audit-2", 2L);
        AuditAnchorDocument firstAnchor = anchor("anchor-1", 1L);
        AuditAnchorDocument secondAnchor = anchor("anchor-2", 2L);
        when(anchorRepository.findByPartitionKeyAndChainPositionIn("source_service:alert-service", Set.of(1L, 2L)))
                .thenReturn(List.of(firstAnchor, secondAnchor));
        when(statusRepository.findByLocalAnchorIds(List.of("anchor-1", "anchor-2")))
                .thenReturn(List.of(status("anchor-1", 1L, ExternalAuditAnchor.STATUS_PUBLISHED)));

        Map<String, AuditExternalAnchorStatus> statuses = lookup.statusesByAuditEventId(List.of(first, second));

        assertThat(statuses)
                .containsEntry("audit-1", AuditExternalAnchorStatus.PUBLISHED)
                .containsEntry("audit-2", AuditExternalAnchorStatus.UNKNOWN);
    }

    @Test
    void shouldMapLocalStatusUnverifiedWithoutGuessingPublishedState() {
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorPublicationStatusRepository statusRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditEventPublicationStatusLookup lookup = new AuditEventPublicationStatusLookup(anchorRepository, statusRepository);
        AuditEventDocument document = event("audit-1", 1L);
        AuditAnchorDocument anchor = anchor("anchor-1", 1L);
        when(anchorRepository.findByPartitionKeyAndChainPositionIn("source_service:alert-service", Set.of(1L)))
                .thenReturn(List.of(anchor));
        when(statusRepository.findByLocalAnchorIds(List.of("anchor-1")))
                .thenReturn(List.of(status("anchor-1", 1L, ExternalAuditAnchor.STATUS_LOCAL_STATUS_UNVERIFIED)));

        Map<String, AuditExternalAnchorStatus> statuses = lookup.statusesByAuditEventId(List.of(document));

        assertThat(statuses).containsEntry("audit-1", AuditExternalAnchorStatus.LOCAL_STATUS_UNVERIFIED);
    }

    @Test
    void shouldResolveSparseReturnedAuditEventsByExactChainPositions() {
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorPublicationStatusRepository statusRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditEventPublicationStatusLookup lookup = new AuditEventPublicationStatusLookup(anchorRepository, statusRepository);
        AuditEventDocument first = event("audit-100", 100L);
        AuditEventDocument second = event("audit-200", 200L);
        AuditAnchorDocument firstAnchor = anchor("anchor-100", 100L);
        AuditAnchorDocument secondAnchor = anchor("anchor-200", 200L);
        when(anchorRepository.findByPartitionKeyAndChainPositionIn("source_service:alert-service", Set.of(100L, 200L)))
                .thenReturn(List.of(firstAnchor, secondAnchor));
        when(statusRepository.findByLocalAnchorIds(List.of("anchor-100", "anchor-200")))
                .thenReturn(List.of(
                        status("anchor-100", 100L, ExternalAuditAnchor.STATUS_PUBLISHED),
                        status("anchor-200", 200L, ExternalAuditAnchor.STATUS_PUBLISHED)
                ));

        Map<String, AuditExternalAnchorStatus> statuses = lookup.statusesByAuditEventId(List.of(first, second));

        assertThat(statuses)
                .containsEntry("audit-100", AuditExternalAnchorStatus.PUBLISHED)
                .containsEntry("audit-200", AuditExternalAnchorStatus.PUBLISHED);
        verify(anchorRepository, never()).findByPartitionKeyAndChainPositionBetween(
                "source_service:alert-service",
                100L,
                200L,
                2
        );
    }

    private AuditEventDocument event(String auditId, long chainPosition) {
        return new AuditEventDocument(
                auditId,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "actor-1",
                "actor-1",
                List.of("FRAUD_OPS_ADMIN"),
                "HUMAN",
                List.of("audit:read"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-04-27T10:00:00Z"),
                "corr-1",
                "req-1",
                "alert-service",
                "source_service:alert-service",
                chainPosition,
                AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE,
                null,
                new AuditEventMetadataSummary("corr-1", "req-1", "alert-service", "1.0", "NONE", null, null, null, null),
                "prev-hash",
                "event-hash",
                "SHA-256",
                "1.0"
        );
    }

    private AuditAnchorDocument anchor(String anchorId, long chainPosition) {
        return new AuditAnchorDocument(
                anchorId,
                Instant.parse("2026-04-27T10:00:00Z"),
                "source_service:alert-service",
                "hash-" + chainPosition,
                chainPosition,
                "SHA-256"
        );
    }

    private ExternalAuditAnchorPublicationStatusDocument status(String anchorId, long chainPosition, String publicationStatus) {
        return new ExternalAuditAnchorPublicationStatusDocument(
                anchorId,
                "source_service:alert-service",
                chainPosition,
                ExternalAuditAnchor.STATUS_PUBLISHED.equals(publicationStatus),
                publicationStatus,
                publicationStatus,
                "PUBLISHED",
                "RECORDED",
                "CREATED",
                false,
                Instant.parse("2026-04-27T10:00:00Z"),
                "object-store",
                "audit-anchors/source_service-alert-service/" + chainPosition + ".json",
                "hash-" + chainPosition,
                "hash-" + chainPosition,
                Instant.parse("2026-04-27T10:00:00Z"),
                "ENFORCED",
                "PUBLISHED",
                "UNSIGNED",
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                null,
                Instant.parse("2026-04-27T10:00:00Z")
        );
    }
}
