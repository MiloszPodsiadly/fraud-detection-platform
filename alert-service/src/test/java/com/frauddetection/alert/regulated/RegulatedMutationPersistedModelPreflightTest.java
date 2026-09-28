package com.frauddetection.alert.regulated;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationPersistedModelPreflightTest {

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
        ).containsExactly("LEGACY", "UNKNOWN");
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
                        "LEGACY",
                        "UPDATE_FRAUD_CASE",
                        "FRAUD_CASE",
                        "PROCESSING"
                ))
        ));

        RegulatedMutationPersistedModelStartupGuard guard = new RegulatedMutationPersistedModelStartupGuard(preflight);

        assertThatThrownBy(() -> guard.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported unfinished persisted commands")
                .hasMessageContaining("command-hash");
    }

    @Test
    void shouldAllowImmutableUnsupportedTerminalCommandsForRetention() {
        RegulatedMutationPersistedModelPreflight preflight = mock(RegulatedMutationPersistedModelPreflight.class);
        when(preflight.inspect(25)).thenReturn(new RegulatedMutationPersistedModelPreflight.Report(
                0,
                2,
                List.of()
        ));

        RegulatedMutationPersistedModelStartupGuard guard = new RegulatedMutationPersistedModelStartupGuard(preflight);

        assertThatCode(() -> guard.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }
}
