package com.frauddetection.alert.regulated;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationDecisionIndexStartupGuardTest {

    @Test
    void missingIndexFailsClosed() {
        assertRejected(List.of(), "INDEX_MISSING");
    }

    @Test
    void nonUniqueIndexFailsClosed() {
        Document index = canonicalIndex().append("unique", false);

        assertRejected(List.of(index), "UNIQUE_REQUIRED");
    }

    @Test
    void incorrectPartialFilterFailsClosed() {
        Document index = canonicalIndex().append(
                "partialFilterExpression",
                new Document("resource_type", "ALERT").append("action", "SUBMIT_ANALYST_DECISION")
        );

        assertRejected(List.of(index), "PARTIAL_FILTER_MISMATCH");
    }

    @Test
    void incorrectOrderedKeyDefinitionFailsClosed() {
        Document index = canonicalIndex().append(
                "key",
                new Document("resource_type", 1).append("resource_id", 1).append("action", 1)
        );

        assertRejected(List.of(index), "KEY_DEFINITION_MISMATCH");
    }

    @Test
    void metadataReadFailureFailsClosed() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.collectionExists(RegulatedMutationDecisionIndexStartupGuard.COLLECTION))
                .thenThrow(new IllegalStateException("mongo unavailable"));

        assertThatThrownBy(() -> new RegulatedMutationDecisionIndexStartupGuard(mongoTemplate).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("METADATA_READ_FAILED")
                .hasMessageNotContaining("mongo unavailable");
    }

    @Test
    void exactCanonicalIndexIsAccepted() {
        assertThatCode(() -> RegulatedMutationDecisionIndexStartupGuard.validateIndexMetadata(
                List.of(canonicalIndex())
        )).doesNotThrowAnyException();
    }

    private void assertRejected(List<Document> indexes, String reason) {
        assertThatThrownBy(() -> RegulatedMutationDecisionIndexStartupGuard.validateIndexMetadata(indexes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_NAME)
                .hasMessageContaining(reason);
    }

    private Document canonicalIndex() {
        return new Document("name", RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_NAME)
                .append("key", Document.parse(RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_KEYS))
                .append("unique", true)
                .append(
                        "partialFilterExpression",
                        Document.parse(RegulatedMutationCommandDocument.DECISION_SLOT_PARTIAL_FILTER)
                );
    }
}
