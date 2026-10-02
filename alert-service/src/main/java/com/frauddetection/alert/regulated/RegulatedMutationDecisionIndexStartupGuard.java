package com.frauddetection.alert.regulated;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegulatedMutationDecisionIndexStartupGuard implements ApplicationRunner {

    static final String COLLECTION = "regulated_mutation_commands";

    private static final Document EXPECTED_KEYS = Document.parse(
            RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_KEYS
    );
    private static final Document EXPECTED_PARTIAL_FILTER = Document.parse(
            RegulatedMutationCommandDocument.DECISION_SLOT_PARTIAL_FILTER
    );
    private static final Bson SUBMIT_DECISION = Filters.and(
            Filters.eq("resource_type", "ALERT"),
            Filters.eq("action", "SUBMIT_ANALYST_DECISION")
    );

    private static final Bson NO_COMMIT_PROOF = Filters.and(
            Filters.eq("response_snapshot", null),
            Filters.eq("outbox_event_id", null),
            Filters.eq("local_commit_marker", null),
            Filters.eq("local_committed_at", null),
            Filters.eq("success_audit_id", null),
            Filters.expr(
                    new Document("$eq", List.of(
                            new Document("$ifNull", List.of(
                                    "$success_audit_recorded",
                                    false
                            )),
                            false
                    ))
            )
    );

    private static final Bson SAFE_RELEASED_COMMAND = Filters.and(
            Filters.in(
                    "state",
                    RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE.name(),
                    RegulatedMutationState.FAILED_BUSINESS_VALIDATION.name()
            ),
            NO_COMMIT_PROOF
    );

    private final MongoTemplate mongoTemplate;

    public RegulatedMutationDecisionIndexStartupGuard(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    void verify() {
        try {
            if (!mongoTemplate.collectionExists(COLLECTION)) {
                throw failure("INDEX_MISSING");
            }
            MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());
            validateIndexMetadata(indexes);
            validatePersistedOwnership(collection);
        } catch (DecisionIndexStartupException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("METADATA_READ_FAILED", exception);
        }
    }

    static void validateIndexMetadata(List<Document> indexes) {
        Document index = indexes == null ? null : indexes.stream()
                .filter(candidate -> RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_NAME.equals(
                        candidate.getString("name")
                ))
                .findFirst()
                .orElse(null);
        if (index == null) {
            throw failure("INDEX_MISSING");
        }
        if (!orderedEntries(EXPECTED_KEYS).equals(orderedEntries(index.get("key", Document.class)))) {
            throw failure("KEY_DEFINITION_MISMATCH");
        }
        if (!Boolean.TRUE.equals(index.getBoolean("unique"))) {
            throw failure("UNIQUE_REQUIRED");
        }
        if (!EXPECTED_PARTIAL_FILTER.equals(index.get("partialFilterExpression", Document.class))) {
            throw failure("PARTIAL_FILTER_MISMATCH");
        }
    }

    private static void validatePersistedOwnership(MongoCollection<Document> collection) {
        Bson invalidOwnership = Filters.and(
                SUBMIT_DECISION,
                Filters.or(
                        Filters.not(Filters.type("decision_slot_claimed", BsonType.BOOLEAN)),
                        Filters.and(SAFE_RELEASED_COMMAND, Filters.ne("decision_slot_claimed", false)),
                        Filters.and(Filters.nor(SAFE_RELEASED_COMMAND), Filters.ne("decision_slot_claimed", true))
                )
        );
        if (collection.countDocuments(invalidOwnership) > 0) {
            throw failure("OWNERSHIP_DATA_INVALID");
        }
        Document duplicate = collection.aggregate(List.of(
                Aggregates.match(Filters.and(SUBMIT_DECISION, Filters.eq("decision_slot_claimed", true))),
                Aggregates.group("$resource_id", Accumulators.sum("count", 1)),
                Aggregates.match(Filters.gt("count", 1))
        )).first();
        if (duplicate != null) {
            throw failure("CONFLICTING_ACTIVE_OWNERS");
        }
    }

    private static List<Map.Entry<String, Object>> orderedEntries(Document document) {
        return document == null ? List.of() : List.copyOf(document.entrySet());
    }

    private static DecisionIndexStartupException failure(String reason) {
        return new DecisionIndexStartupException("Regulated mutation decision index startup guard failed: index="
                + RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_NAME + "; reason=" + reason);
    }

    private static DecisionIndexStartupException failure(String reason, RuntimeException cause) {
        return new DecisionIndexStartupException("Regulated mutation decision index startup guard failed: index="
                + RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_NAME + "; reason=" + reason, cause);
    }

    private static final class DecisionIndexStartupException extends IllegalStateException {
        private DecisionIndexStartupException(String message) {
            super(message);
        }

        private DecisionIndexStartupException(String message, RuntimeException cause) {
            super(message, cause);
        }
    }
}
