package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationPersistedModelPreflightTest {

    private final RegulatedMutationPersistedModelPreflight preflight =
            new RegulatedMutationPersistedModelPreflight(mock(MongoTemplate.class));

    @Test
    void removedAlertOwnedOutboxActionIsUnsupportedAsAnActiveCommand() {
        Document document = currentDocument(
                "RESOLVE_DECISION_OUTBOX_CONFIRMATION",
                "DECISION_OUTBOX"
        );

        assertThat(preflight.contractCategory(document)).isEqualTo("UNSUPPORTED_ACTION_RESOURCE_PAIR");
    }

    @Test
    void shouldAllowValidCanonicalActionResourcePair() {
        assertThat(preflight.contractCategory(currentDocument(
                AuditAction.SUBMIT_ANALYST_DECISION.name(),
                AuditResourceType.ALERT.name()
        ))).isEqualTo("SUPPORTED");
    }

    @Test
    void removedFinalizedVisibleStateIsUnsupported() {
        Document document = currentDocument(
                AuditAction.SUBMIT_ANALYST_DECISION.name(),
                AuditResourceType.ALERT.name()
        );
        document.put("state", "FINALIZED_VISIBLE");

        assertThat(preflight.contractCategory(document)).isEqualTo("UNKNOWN_STATE");
    }

    @Test
    void shouldBlockUnknownAction() {
        assertThat(preflight.contractCategory(currentDocument(
                "REMOVED_ACTION",
                AuditResourceType.ALERT.name()
        ))).isEqualTo("UNKNOWN_ACTION");
    }

    @Test
    void shouldBlockUnknownResourceType() {
        assertThat(preflight.contractCategory(currentDocument(
                AuditAction.SUBMIT_ANALYST_DECISION.name(),
                "REMOVED_RESOURCE"
        ))).isEqualTo("UNKNOWN_RESOURCE_TYPE");
    }

    @Test
    void shouldBlockValidEnumsInUnsupportedCombination() {
        assertThat(preflight.contractCategory(currentDocument(
                AuditAction.SUBMIT_ANALYST_DECISION.name(),
                AuditResourceType.TRUST_INCIDENT.name()
        ))).isEqualTo("UNSUPPORTED_ACTION_RESOURCE_PAIR");
    }

    @Test
    void shouldAllowCurrentSupportedPairWithCompletePersistedContract() {
        assertThat(preflight.contractCategory(currentDocument(
                AuditAction.UPDATE_FRAUD_CASE.name(),
                AuditResourceType.FRAUD_CASE.name()
        ))).isEqualTo("SUPPORTED");
    }

    @Test
    void shouldClassifyMissingNullAndNonStringActionAndResourceType() {
        Document missingAction = currentDocument(
                AuditAction.SUBMIT_ANALYST_DECISION.name(),
                AuditResourceType.ALERT.name()
        );
        missingAction.remove("action");
        Document nullAction = currentDocument(null, AuditResourceType.ALERT.name());
        Document nonStringAction = currentDocument(7, AuditResourceType.ALERT.name());
        Document missingResource = currentDocument(
                AuditAction.SUBMIT_ANALYST_DECISION.name(),
                AuditResourceType.ALERT.name()
        );
        missingResource.remove("resource_type");
        Document nullResource = currentDocument(AuditAction.SUBMIT_ANALYST_DECISION.name(), null);
        Document nonStringResource = currentDocument(AuditAction.SUBMIT_ANALYST_DECISION.name(), 7);

        assertThat(preflight.contractCategory(missingAction)).isEqualTo("MISSING_ACTION");
        assertThat(preflight.contractCategory(nullAction)).isEqualTo("NULL_ACTION");
        assertThat(preflight.contractCategory(nonStringAction)).isEqualTo("NON_STRING_ACTION");
        assertThat(preflight.contractCategory(missingResource)).isEqualTo("MISSING_RESOURCE_TYPE");
        assertThat(preflight.contractCategory(nullResource)).isEqualTo("NULL_RESOURCE_TYPE");
        assertThat(preflight.contractCategory(nonStringResource)).isEqualTo("NON_STRING_RESOURCE_TYPE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldInspectRawDocumentsWithoutDeserializingUnknownModelVersions() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(mongoTemplate.getCollection(RegulatedMutationPersistedModelPreflight.COLLECTION)).thenReturn(collection);
        when(collection.countDocuments(any(Bson.class))).thenReturn(2L, 3L);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.projection(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(10)).thenReturn(iterable);
        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(0);
            consumer.accept(new Document("_id", "raw-command-id")
                    .append("mutation_model_version", "LEGACY_REGULATED_MUTATION")
                    .append("execution_status", "PROCESSING")
                    .append("action", "UPDATE_FRAUD_CASE")
                    .append("resource_type", "FRAUD_CASE"));
            consumer.accept(new Document("_id", "unknown-command-id")
                    .append("mutation_model_version", "UNRECOGNIZED_VERSION")
                    .append("execution_status", "COMPLETED"));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        RegulatedMutationPersistedModelPreflight.Report report =
                new RegulatedMutationPersistedModelPreflight(mongoTemplate).inspect(10);

        assertThat(report.unsupportedUnfinishedCount()).isEqualTo(2);
        assertThat(report.unsupportedTerminalCount()).isEqualTo(3);
        assertThat(report.samples()).extracting(
                RegulatedMutationPersistedModelPreflight.UnsupportedCommand::modelCategory
        ).containsExactly("UNKNOWN", "UNKNOWN");
        assertThat(report.samples())
                .extracting(RegulatedMutationPersistedModelPreflight.UnsupportedCommand::commandIdHash)
                .noneMatch(hash -> hash.contains("raw-command-id") || hash.contains("unknown-command-id"));
    }

    @Test
    void shouldFailStartupWhenUnsupportedUnfinishedCommandsExist() {
        RegulatedMutationPersistedModelPreflight preflight = mock(RegulatedMutationPersistedModelPreflight.class);
        when(preflight.inspect(25)).thenReturn(new RegulatedMutationPersistedModelPreflight.Report(
                1,
                0,
                List.of(new RegulatedMutationPersistedModelPreflight.UnsupportedCommand(
                        "command-hash",
                        "UNKNOWN",
                        "UPDATE_FRAUD_CASE",
                        "FRAUD_CASE",
                        "PROCESSING"
                ))
        ));

        RegulatedMutationPersistedModelStartupGuard guard = new RegulatedMutationPersistedModelStartupGuard(preflight);

        assertThatThrownBy(() -> guard.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported persisted commands in the active collection")
                .hasMessageContaining("unfinishedCount=1")
                .hasMessageContaining("terminalCount=0")
                .hasMessageContaining("command-hash");
    }

    @Test
    void shouldFailStartupWhenUnsupportedTerminalCommandsRemainInActiveCollection() {
        RegulatedMutationPersistedModelPreflight preflight = mock(RegulatedMutationPersistedModelPreflight.class);
        when(preflight.inspect(25)).thenReturn(new RegulatedMutationPersistedModelPreflight.Report(
                0,
                2,
                List.of(new RegulatedMutationPersistedModelPreflight.UnsupportedCommand(
                        "terminal-command-hash",
                        "UNKNOWN",
                        "SUBMIT_ANALYST_DECISION",
                        "ALERT",
                        "COMPLETED"
                ))
        ));

        RegulatedMutationPersistedModelStartupGuard guard = new RegulatedMutationPersistedModelStartupGuard(preflight);

        assertThatThrownBy(() -> guard.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported persisted commands in the active collection")
                .hasMessageContaining("unfinishedCount=0")
                .hasMessageContaining("terminalCount=2")
                .hasMessageContaining("terminal-command-hash");
    }

    private Document currentDocument(Object action, Object resourceType) {
        return new Document("mutation_model_version", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name())
                .append("revision", 0L)
                .append("state", RegulatedMutationState.REQUESTED.name())
                .append("execution_status", RegulatedMutationExecutionStatus.NEW.name())
                .append("action", action)
                .append("resource_type", resourceType);
    }
}
