package com.frauddetection.alert.outbox;

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

class TransactionalOutboxPersistedContractPreflightTest {

    @Test
    @SuppressWarnings("unchecked")
    void inspectsRetiredRawShapeWithoutOutboxDeserializationAndIncludesTerminalRecords() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(mongoTemplate.getCollection(TransactionalOutboxPersistedContractPreflight.COLLECTION))
                .thenReturn(collection);
        when(collection.countDocuments(any(Bson.class))).thenReturn(2L, 3L);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.projection(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(10)).thenReturn(iterable);
        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(0);
            consumer.accept(new Document("_id", "pending-event-id")
                    .append("status", "PUBLISH_CONFIRMATION_UNKNOWN")
                    .append("resolution_pending", true)
                    .append("resolution_reason", "ambiguous request reason"));
            consumer.accept(new Document("_id", "terminal-event-id")
                    .append("status", "PUBLISHED")
                    .append("resolution_pending", false)
                    .append("resolution_reason", "ambiguous approval reason"));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        TransactionalOutboxPersistedContractPreflight.Report report =
                new TransactionalOutboxPersistedContractPreflight(mongoTemplate).inspect(10);

        assertThat(report.unsupportedUnfinishedCount()).isEqualTo(2);
        assertThat(report.unsupportedTerminalCount()).isEqualTo(3);
        assertThat(report.samples())
                .extracting(TransactionalOutboxPersistedContractPreflight.UnsupportedOutboxRecord::resolutionPhase)
                .containsExactly("PENDING_REQUEST", "COMPLETED_OR_UNSET_APPROVAL");
        assertThat(report.samples())
                .extracting(TransactionalOutboxPersistedContractPreflight.UnsupportedOutboxRecord::eventIdHash)
                .noneMatch(hash -> hash.contains("pending-event-id") || hash.contains("terminal-event-id"));
    }

    @Test
    void cleanCanonicalCollectionDoesNotBlockStartup() throws Exception {
        TransactionalOutboxPersistedContractPreflight preflight =
                mock(TransactionalOutboxPersistedContractPreflight.class);
        when(preflight.inspect(25)).thenReturn(
                new TransactionalOutboxPersistedContractPreflight.Report(0, 0, List.of()));

        TransactionalOutboxPersistedContractStartupGuard guard =
                new TransactionalOutboxPersistedContractStartupGuard(preflight);

        guard.run(mock(ApplicationArguments.class));
    }

    @Test
    void retiredShapeBlocksStartupEvenWhenOnlyTerminalRecordsRemain() {
        TransactionalOutboxPersistedContractPreflight preflight =
                mock(TransactionalOutboxPersistedContractPreflight.class);
        when(preflight.inspect(25)).thenReturn(new TransactionalOutboxPersistedContractPreflight.Report(
                0,
                1,
                List.of(new TransactionalOutboxPersistedContractPreflight.UnsupportedOutboxRecord(
                        "event-hash",
                        "PUBLISHED",
                        "COMPLETED_OR_UNSET_APPROVAL",
                        false,
                        false
                ))
        ));

        TransactionalOutboxPersistedContractStartupGuard guard =
                new TransactionalOutboxPersistedContractStartupGuard(preflight);

        assertThatThrownBy(() -> guard.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired resolution_reason")
                .hasMessageContaining("unfinishedCount=0")
                .hasMessageContaining("terminalCount=1")
                .hasMessageContaining("event-hash");
    }
}
