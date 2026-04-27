package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditIntegrityServiceTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuditIntegrityService service = new AuditIntegrityService(
            repository,
            anchorRepository,
            new AuditIntegrityQueryParser(),
            new AlertServiceMetrics(meterRegistry),
            auditService
    );

    @Test
    void shouldVerifyValidBoundedHashChainAndAuditTheRead() {
        AuditEventDocument first = document("audit-1", "alert-1", "2026-04-26T09:00:00Z", null);
        AuditEventDocument second = document("audit-2", "alert-2", "2026-04-26T09:01:00Z", first.eventHash());
        when(repository.findIntegrityWindow("alert-service", null, null, 100))
                .thenReturn(List.of(first, second));
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(second));
        when(anchorRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(AuditAnchorDocument.from("anchor-1", second, 2)));
        when(repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(2L);

        AuditIntegrityResponse response = service.verify(null, null, "alert-service", null);

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.checked()).isEqualTo(2);
        assertThat(response.limit()).isEqualTo(100);
        assertThat(response.firstEventHash()).isEqualTo(first.eventHash());
        assertThat(response.lastEventHash()).isEqualTo(second.eventHash());
        assertThat(response.partitionKey()).isEqualTo(AuditEventDocument.PARTITION_KEY);
        assertThat(response.lastAnchorHash()).isEqualTo(second.eventHash());
        assertThat(response.violations()).isEmpty();
        verify(auditService).audit(
                eq(AuditAction.VERIFY_AUDIT_INTEGRITY),
                eq(AuditResourceType.AUDIT_INTEGRITY),
                isNull(),
                isNull(),
                eq("audit-integrity-reader"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(AuditEventMetadataSummary.class)
        );
    }

    @Test
    void shouldDetectTamperedEventAndPreviousHashMismatch() {
        AuditEventDocument first = document("audit-1", "alert-1", "2026-04-26T09:00:00Z", null);
        AuditEventDocument tampered = document("audit-2", "alert-2", "2026-04-26T09:01:00Z", "wrong-previous")
                .withEventHash("tampered-hash");
        when(repository.findIntegrityWindow(null, null, null, 100))
                .thenReturn(List.of(first, tampered));
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(tampered));
        when(anchorRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(AuditAnchorDocument.from("anchor-1", tampered, 2)));
        when(repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(2L);

        AuditIntegrityResponse response = service.verify(null, null, null, null);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations())
                .extracting(AuditIntegrityViolation::violationType)
                .contains("EVENT_HASH_MISMATCH", "PREVIOUS_HASH_MISMATCH");
        assertThat(meterRegistry.get("fraud_platform_audit_integrity_violations_total")
                .tag("violation_type", "EVENT_HASH_MISMATCH")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldDetectAnchorMismatch() {
        AuditEventDocument first = document("audit-1", "alert-1", "2026-04-26T09:00:00Z", null);
        when(repository.findIntegrityWindow("alert-service", null, null, 100))
                .thenReturn(List.of(first));
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(first));
        when(anchorRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(new AuditAnchorDocument(
                        "anchor-1",
                        Instant.parse("2026-04-26T09:01:00Z"),
                        AuditEventDocument.PARTITION_KEY,
                        "wrong-anchor-hash",
                        1,
                        AuditEventDocument.HASH_ALGORITHM
                )));
        when(repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(1L);

        AuditIntegrityResponse response = service.verify(null, null, "alert-service", null);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations())
                .extracting(AuditIntegrityViolation::violationType)
                .contains("ANCHOR_HASH_MISMATCH");
    }

    @Test
    void shouldDetectChainForkWithinWindow() {
        AuditEventDocument first = document("audit-1", "alert-1", "2026-04-26T09:00:00Z", null);
        AuditEventDocument second = document("audit-2", "alert-2", "2026-04-26T09:01:00Z", first.eventHash());
        AuditEventDocument fork = document("audit-3", "alert-3", "2026-04-26T09:02:00Z", first.eventHash());
        when(repository.findIntegrityWindow("alert-service", null, null, 100))
                .thenReturn(List.of(first, second, fork));
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(fork));
        when(anchorRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(AuditAnchorDocument.from("anchor-1", fork, 3)));
        when(repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(3L);

        AuditIntegrityResponse response = service.verify(null, null, "alert-service", null);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations())
                .extracting(AuditIntegrityViolation::violationType)
                .contains("CHAIN_FORK_DETECTED", "PREVIOUS_HASH_MISMATCH");
    }

    @Test
    void shouldDetectFirstDeletionThroughAnchorChainPositionMismatch() {
        AuditEventDocument first = document("audit-1", "alert-1", "2026-04-26T09:00:00Z", null);
        AuditEventDocument second = document("audit-2", "alert-2", "2026-04-26T09:01:00Z", first.eventHash());
        when(repository.findIntegrityWindow("alert-service", null, null, 100))
                .thenReturn(List.of(second));
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(second));
        when(anchorRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY))
                .thenReturn(java.util.Optional.of(AuditAnchorDocument.from("anchor-1", second, 2)));
        when(repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(1L);

        AuditIntegrityResponse response = service.verify(null, null, "alert-service", null);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations())
                .extracting(AuditIntegrityViolation::violationType)
                .contains("ANCHOR_CHAIN_POSITION_MISMATCH");
    }

    @Test
    void shouldRejectInvalidIntegrityQuery() {
        assertThatThrownBy(() -> service.verify("bad", null, null, 100))
                .isInstanceOf(InvalidAuditEventQueryException.class);
        assertThatThrownBy(() -> service.verify(null, null, "unknown-service", 100))
                .isInstanceOf(InvalidAuditEventQueryException.class);
        assertThatThrownBy(() -> service.verify(null, null, null, 501))
                .isInstanceOf(InvalidAuditEventQueryException.class);
        assertThatThrownBy(() -> service.verify("2026-04-27T00:00:00Z", "2026-04-26T00:00:00Z", null, 100))
                .isInstanceOf(InvalidAuditEventQueryException.class);
    }

    @Test
    void shouldReturnUnavailableWithoutLeakingDatastoreDetails() {
        when(repository.findIntegrityWindow(null, null, null, 25))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        AuditIntegrityResponse response = service.verify(null, null, null, 25);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.checked()).isZero();
        assertThat(response.reasonCode()).isEqualTo("AUDIT_STORE_UNAVAILABLE");
        assertThat(response.violations()).isEmpty();
        assertThat(response.toString()).doesNotContain("mongo down", "DataAccessResourceFailureException");
    }

    private AuditEventDocument document(String auditId, String resourceId, String occurredAt, String previousHash) {
        return AuditEventDocument.from(auditId, new AuditEvent(
                new AuditActor("admin-1", Set.of("FRAUD_OPS_ADMIN"), Set.of("audit:read")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                resourceId,
                Instant.parse(occurredAt),
                "corr-1",
                AuditOutcome.SUCCESS,
                null
        ), previousHash);
    }
}
