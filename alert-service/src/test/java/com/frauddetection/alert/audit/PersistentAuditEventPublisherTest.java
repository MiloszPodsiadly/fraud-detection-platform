package com.frauddetection.alert.audit;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentAuditEventPublisherTest {

    @Test
    void shouldPersistAppendOnlyAuditDocumentWithoutPayloadData() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        AuditChainLockRepository lockRepository = mock(AuditChainLockRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(meterRegistry),
                3,
                1L
        );
        AuditEvent event = new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of("alert:decision:submit")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-04-23T10:00:00Z"),
                "corr-1",
                AuditOutcome.SUCCESS,
                null
        );

        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(Optional.empty());
        when(repository.insert(any(AuditEventDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publish(event);

        ArgumentCaptor<AuditEventDocument> documentCaptor = ArgumentCaptor.forClass(AuditEventDocument.class);
        verify(repository).insert(documentCaptor.capture());
        AuditEventDocument document = documentCaptor.getValue();
        assertThat(document.auditId()).isNotBlank();
        assertThat(document.eventType()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION);
        assertThat(document.actorId()).isEqualTo("analyst-1");
        assertThat(document.actorDisplayName()).isEqualTo("analyst-1");
        assertThat(document.actorRoles()).containsExactly("ANALYST");
        assertThat(document.actorType()).isEqualTo("HUMAN");
        assertThat(document.actorAuthorities()).containsExactly("alert:decision:submit");
        assertThat(document.action()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION);
        assertThat(document.resourceType()).isEqualTo(AuditResourceType.ALERT);
        assertThat(document.resourceId()).isEqualTo("alert-1");
        assertThat(document.createdAt()).isEqualTo(Instant.parse("2026-04-23T10:00:00Z"));
        assertThat(document.correlationId()).isEqualTo("corr-1");
        assertThat(document.requestId()).isNull();
        assertThat(document.sourceService()).isEqualTo("alert-service");
        assertThat(document.partitionKey()).isEqualTo(AuditEventDocument.PARTITION_KEY);
        assertThat(document.chainPosition()).isEqualTo(1L);
        assertThat(document.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(document.failureCategory()).isEqualTo(AuditFailureCategory.NONE);
        assertThat(document.failureReason()).isNull();
        assertThat(document.previousEventHash()).isNull();
        assertThat(document.eventHash()).isNotBlank();
        assertThat(document.hashAlgorithm()).isEqualTo("SHA-256");
        assertThat(document.schemaVersion()).isEqualTo("1.0");
        assertThat(java.util.Arrays.stream(document.getClass().getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("requestBody", "responseBody", "transactionPayload", "featureVector", "customerId", "accountId", "cardNumber");
        assertThat(meterRegistry.get("fraud_platform_audit_events_persisted_total")
                .tags("event_type", "submit_analyst_decision", "outcome", "success")
                .counter()
                .count()).isEqualTo(1.0d);
        ArgumentCaptor<AuditAnchorDocument> anchorCaptor = ArgumentCaptor.forClass(AuditAnchorDocument.class);
        verify(anchorRepository).insert(anchorCaptor.capture());
        assertThat(anchorCaptor.getValue().partitionKey()).isEqualTo(AuditEventDocument.PARTITION_KEY);
        assertThat(anchorCaptor.getValue().lastEventHash()).isEqualTo(document.eventHash());
        assertThat(anchorCaptor.getValue().chainPosition()).isEqualTo(1L);
    }

    @Test
    void shouldContinueChainAfterLegacyHeadWithoutChainPosition() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        AuditChainLockRepository lockRepository = mock(AuditChainLockRepository.class);
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(new SimpleMeterRegistry())
        );
        AuditEvent event = new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of("alert:decision:submit")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-2",
                Instant.parse("2026-04-23T10:00:00Z"),
                "corr-2",
                AuditOutcome.SUCCESS,
                null
        );
        AuditEventDocument currentShape = AuditEventDocument.from("audit-legacy", event, "previous-hash", 1L);
        AuditEventDocument legacyHead = new AuditEventDocument(
                currentShape.auditId(),
                currentShape.eventType(),
                currentShape.actorId(),
                currentShape.actorDisplayName(),
                currentShape.actorRoles(),
                currentShape.actorType(),
                currentShape.actorAuthorities(),
                currentShape.action(),
                currentShape.resourceType(),
                currentShape.resourceId(),
                currentShape.createdAt(),
                currentShape.correlationId(),
                currentShape.requestId(),
                currentShape.sourceService(),
                currentShape.partitionKey(),
                0L,
                currentShape.outcome(),
                currentShape.failureCategory(),
                currentShape.failureReason(),
                currentShape.metadataSummary(),
                currentShape.previousEventHash(),
                currentShape.eventHash(),
                currentShape.hashAlgorithm(),
                currentShape.schemaVersion()
        );

        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(Optional.of(legacyHead));
        when(repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(7L);
        when(repository.insert(any(AuditEventDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publish(event);

        ArgumentCaptor<AuditEventDocument> documentCaptor = ArgumentCaptor.forClass(AuditEventDocument.class);
        verify(repository).insert(documentCaptor.capture());
        assertThat(documentCaptor.getValue().chainPosition()).isEqualTo(8L);
        assertThat(documentCaptor.getValue().previousEventHash()).isEqualTo(legacyHead.eventHash());
    }

    @Test
    void shouldFailClearlyWhenAuditPersistenceIsUnavailable() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        AuditChainLockRepository lockRepository = mock(AuditChainLockRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenThrow(new DataAccessResourceFailureException("mongo down"));
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(meterRegistry),
                3,
                1L
        );

        assertThatThrownBy(() -> publisher.publish(new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of()),
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                Instant.parse("2026-04-23T10:00:00Z"),
                null,
                AuditOutcome.SUCCESS,
                null
        ))).isInstanceOf(AuditPersistenceUnavailableException.class)
                .extracting(Throwable::getMessage)
                .isNull();

        assertThat(meterRegistry.get("fraud_platform_audit_persistence_failures_total")
                .tag("event_type", "update_fraud_case")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldProduceDeterministicHashForSameImmutableEventInput() {
        AuditEvent event = new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of("alert:decision:submit")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-04-23T10:00:00.123456789Z"),
                "corr-1",
                AuditOutcome.SUCCESS,
                null
        );

        AuditEventDocument first = AuditEventDocument.from("audit-1", event, "previous-hash");
        AuditEventDocument second = AuditEventDocument.from("audit-1", event, "previous-hash");
        AuditEventDocument persistedMongoShape = new AuditEventDocument(
                first.auditId(),
                first.eventType(),
                first.actorId(),
                first.actorDisplayName(),
                first.actorRoles(),
                first.actorType(),
                first.actorAuthorities(),
                first.action(),
                first.resourceType(),
                first.resourceId(),
                first.createdAt().truncatedTo(ChronoUnit.MILLIS),
                first.correlationId(),
                first.requestId(),
                first.sourceService(),
                first.partitionKey(),
                first.chainPosition(),
                first.outcome(),
                first.failureCategory(),
                first.failureReason(),
                first.metadataSummary(),
                first.previousEventHash(),
                first.eventHash(),
                first.hashAlgorithm(),
                first.schemaVersion()
        );

        assertThat(first.eventHash()).isEqualTo(second.eventHash());
        assertThat(AuditEventHasher.matches(first)).isTrue();
        assertThat(AuditEventHasher.matches(persistedMongoShape)).isTrue();
    }

    @Test
    void shouldExposeInsertOnlyRepositoryContract() {
        assertThat(Arrays.stream(AuditEventRepository.class.getDeclaredMethods()).map(Method::getName))
                .contains("insert", "findLatestByPartitionKey", "countByPartitionKey", "findHeadWindow", "findFullChain")
                .doesNotContain("save", "update", "delete", "deleteById", "deleteAll");
        assertThat(Arrays.stream(AuditAnchorRepository.class.getDeclaredMethods()).map(Method::getName))
                .contains("insert", "findLatestByPartitionKey")
                .doesNotContain("save", "update", "delete", "deleteById", "deleteAll");
    }

    @Test
    void shouldSerializeConcurrentWritesWithoutForkOrDuplicateChainPosition() throws Exception {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        AuditChainLockRepository lockRepository = mock(AuditChainLockRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(meterRegistry),
                3,
                1L
        );
        AtomicReference<AuditEventDocument> latest = new AtomicReference<>();
        List<AuditEventDocument> inserted = java.util.Collections.synchronizedList(new ArrayList<>());
        Semaphore lock = new Semaphore(1);
        doAnswer(invocation -> {
            lock.acquire();
            return new AuditChainLockDocument(invocation.getArgument(0), invocation.getArgument(1), Instant.now().plusSeconds(30));
        }).when(lockRepository).acquire(eq(AuditEventDocument.PARTITION_KEY), any(String.class));
        doAnswer(invocation -> {
            lock.release();
            return null;
        }).when(lockRepository).release(eq(AuditEventDocument.PARTITION_KEY), any(String.class));
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenAnswer(invocation -> Optional.ofNullable(latest.get()));
        when(repository.insert(any(AuditEventDocument.class))).thenAnswer(invocation -> {
            AuditEventDocument document = invocation.getArgument(0);
            inserted.add(document);
            latest.set(document);
            return document;
        });

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> publishAfterStart(publisher, start, "alert-1"));
            var second = executor.submit(() -> publishAfterStart(publisher, start, "alert-2"));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertThat(inserted).hasSize(2);
        assertThat(inserted).extracting(AuditEventDocument::chainPosition).containsExactly(1L, 2L);
        assertThat(inserted.get(1).previousEventHash()).isEqualTo(inserted.get(0).eventHash());
    }

    @Test
    void shouldFailExplicitlyWhenChainLockConflicts() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        AuditChainLockRepository lockRepository = mock(AuditChainLockRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(lockRepository.acquire(eq(AuditEventDocument.PARTITION_KEY), any(String.class)))
                .thenThrow(new AuditChainConflictException("locked"));
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(meterRegistry),
                3,
                1L
        );

        assertThatThrownBy(() -> publisher.publish(event("alert-1")))
                .isInstanceOf(AuditPersistenceUnavailableException.class);
        assertThat(meterRegistry.get("fraud_platform_audit_chain_conflicts_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFailExplicitlyWhenAnchorWriteFails() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        AuditChainLockRepository lockRepository = mock(AuditChainLockRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(Optional.empty());
        when(repository.insert(any(AuditEventDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(anchorRepository.insert(any(AuditAnchorDocument.class))).thenThrow(new DataAccessResourceFailureException("mongo down"));
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(meterRegistry)
        );

        assertThatThrownBy(() -> publisher.publish(event("alert-1")))
                .isInstanceOf(AuditPersistenceUnavailableException.class);
        assertThat(meterRegistry.get("fraud_platform_audit_anchor_write_failures_total").counter().count()).isEqualTo(1.0d);
    }

    void shouldRejectApplicationLevelAuditMutationAttempts() {
        AuditImmutabilityGuard guard = new AuditImmutabilityGuard();

        assertThatThrownBy(() -> guard.rejectMutation("update"))
                .isInstanceOf(AuditImmutableMutationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void shouldNotContainDurableAuditUpdateOrDeleteOperations() throws Exception {
        Path auditSource = Path.of("src/main/java/com/frauddetection/alert/audit");
        List<String> forbiddenFragments = List.of(
                "mongoTemplate.save",
                "mongoTemplate.remove",
                "updateFirst(",
                "updateMulti(",
                "updateOne(",
                "findAndModify("
        );

        try (var paths = Files.walk(auditSource)) {
            List<Path> offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("\\read\\"))
                    .filter(path -> !path.endsWith("AuditChainLockRepository.java"))
                    .filter(path -> {
                        try {
                            String content = Files.readString(path);
                            return forbiddenFragments.stream().anyMatch(content::contains);
                        } catch (Exception exception) {
                            return true;
                        }
                    })
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }

    private void publishAfterStart(PersistentAuditEventPublisher publisher, CountDownLatch start, String resourceId) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        publisher.publish(event(resourceId));
    }

    private AuditEvent event(String resourceId) {
        return new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of("alert:decision:submit")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                resourceId,
                Instant.parse("2026-04-23T10:00:00Z"),
                "corr-1",
                AuditOutcome.SUCCESS,
                null
        );
    }
}
