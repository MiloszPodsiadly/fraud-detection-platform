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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentAuditEventPublisherTest {

    @Test
    void shouldPersistAppendOnlyAuditDocumentWithoutPayloadData() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                new AlertServiceMetrics(meterRegistry)
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
        when(repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenReturn(1L);

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
    void shouldFailClearlyWhenAuditPersistenceIsUnavailable() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)).thenThrow(new DataAccessResourceFailureException("mongo down"));
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                new AlertServiceMetrics(meterRegistry)
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
                .contains("insert", "findLatestByPartitionKey", "countByPartitionKey")
                .doesNotContain("save", "update", "delete", "deleteById", "deleteAll");
        assertThat(Arrays.stream(AuditAnchorRepository.class.getDeclaredMethods()).map(Method::getName))
                .contains("insert", "findLatestByPartitionKey")
                .doesNotContain("save", "update", "delete", "deleteById", "deleteAll");
    }

    @Test
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
}
