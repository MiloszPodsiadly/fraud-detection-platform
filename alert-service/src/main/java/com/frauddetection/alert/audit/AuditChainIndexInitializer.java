package com.frauddetection.alert.audit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Projections;
import jakarta.annotation.PostConstruct;
import org.bson.BsonType;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AuditChainIndexInitializer {

    private static final String PARTITION_KEY = "partition_key";
    private static final String CHAIN_POSITION = "chain_position";
    private static final String REQUEST_ID = "request_id";

    private final MongoTemplate mongoTemplate;

    public AuditChainIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void initialize() {
        ensureIndexes();
    }

    void ensureIndexes() {
        IndexPreflight preflight = preflight();
        if (!preflight.safeToInitialize()) {
            throw new IllegalStateException("Audit index preflight failed: " + preflight.diagnostic());
        }
        ensureUniquePositionIndex("audit_events", "audit_partition_chain_position_uidx");
        ensureUniquePositionIndex("audit_chain_anchors", "audit_anchor_partition_chain_position_uidx");
        ensureUniqueRequestIdIndex();
    }

    public boolean hasRequiredUniqueIndexes() {
        return preflight().ready();
    }

    /** Read-only inspection used before bootstrap and by readiness checks. */
    public IndexPreflight preflight() {
        return new IndexPreflight(List.of(
                inspect("audit_events", "chain-position", positionKey(), positionPartialFilter(),
                        "audit_partition_chain_position_uidx", true),
                inspect("audit_chain_anchors", "chain-position", positionKey(), positionPartialFilter(),
                        "audit_anchor_partition_chain_position_uidx", true),
                inspect("audit_events", "request-id", requestIdKey(), requestIdPartialFilter(),
                        "audit_request_id_uidx", false)
        ));
    }

    private IndexCheck inspect(String collectionName, String purpose, Document expectedKey,
            Document expectedFilter, String expectedName, boolean partitionPosition) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
        List<Document> matching = collection.listIndexes(Document.class).into(new java.util.ArrayList<>()).stream()
                .filter(index -> sameKeyFields(index, expectedKey))
                .toList();
        Document exact = matching.stream().filter(index -> keyMatches(index, expectedKey)
                && isExactIndex(index, expectedFilter)).findFirst().orElse(null);
        Document incompatible = matching.stream().filter(index -> !keyMatches(index, expectedKey)
                        || !isExactIndex(index, expectedFilter))
                .filter(index -> Boolean.TRUE.equals(index.getBoolean("unique"))
                        || matching.stream().noneMatch(candidate -> isExactIndex(candidate, expectedFilter)))
                .findFirst().orElse(null);
        boolean duplicates = (exact == null || incompatible != null)
                && hasDuplicateValues(collection, partitionPosition ? CHAIN_POSITION : REQUEST_ID, partitionPosition);
        if (incompatible != null) {
            IndexState state = isLegacySparse(incompatible, partitionPosition)
                    ? IndexState.LEGACY_SPARSE : IndexState.INCOMPATIBLE;
            return new IndexCheck(collectionName, purpose, expectedName, state,
                    indexName(incompatible), duplicates,
                    state == IndexState.LEGACY_SPARSE
                            ? "Legacy unique sparse index requires an operator-approved maintenance migration."
                            : "Existing index key matches, but uniqueness, filter, or ordering differs from the canonical contract.");
        }
        if (exact != null) {
            return new IndexCheck(collectionName, purpose, expectedName, IndexState.PRESENT,
                    indexName(exact), false, "Canonical unique index is installed.");
        }
        if (duplicates) {
            return new IndexCheck(collectionName, purpose, expectedName, IndexState.DUPLICATE_DATA,
                    null, true, "Duplicate indexed values exist; bootstrap will not mutate indexes or evidence.");
        }
        return new IndexCheck(collectionName, purpose, expectedName, IndexState.MISSING,
                null, false, "Canonical index is absent and may be created safely.");
    }

    private void ensureUniquePositionIndex(String collectionName, String indexName) {
        ensureIndex(collectionName, positionKey(), positionPartialFilter(), indexName);
    }

    private void ensureUniqueRequestIdIndex() {
        ensureIndex("audit_events", requestIdKey(), requestIdPartialFilter(), "audit_request_id_uidx");
    }

    private void ensureIndex(String collectionName, Document key, Document partialFilter, String indexName) {
        IndexCheck current = preflight().indexes().stream()
                .filter(check -> check.collection().equals(collectionName)
                        && check.expectedName().equals(indexName))
                .findFirst().orElseThrow();
        if (current.state() == IndexState.PRESENT) return;
        if (current.state() != IndexState.MISSING) {
            throw new IllegalStateException("Audit index preflight failed: " + current.diagnostic());
        }
        try {
            mongoTemplate.getCollection(collectionName).createIndex(
                    key,
                    new IndexOptions().name(indexName).unique(true).partialFilterExpression(partialFilter)
            );
        } catch (RuntimeException createFailure) {
            IndexCheck afterRace = preflight().indexes().stream()
                    .filter(check -> check.collection().equals(collectionName)
                            && check.expectedName().equals(indexName))
                    .findFirst().orElseThrow();
            if (afterRace.state() == IndexState.PRESENT) return;
            throw new IllegalStateException("Audit index creation failed; post-race preflight: "
                    + afterRace.diagnostic(), createFailure);
        }
    }

    private boolean isExactIndex(Document index, Document expectedFilter) {
        return Boolean.TRUE.equals(index.getBoolean("unique"))
                && !index.containsKey("collation")
                && expectedFilter.equals(index.get("partialFilterExpression"));
    }

    private boolean isLegacySparse(Document index, boolean partitionPosition) {
        if (!Boolean.TRUE.equals(index.getBoolean("unique"))
                || !Boolean.TRUE.equals(index.getBoolean("sparse"))
                || index.containsKey("partialFilterExpression")) return false;
        return partitionPosition || hasRequestIdKey(index);
    }

    private boolean hasRequestIdKey(Document index) {
        return keyMatches(index, requestIdKey());
    }

    private boolean keyMatches(Document index, Document expectedKey) {
        Object rawKey = index.get("key");
        if (!(rawKey instanceof Document key)) return false;
        return List.copyOf(key.entrySet()).equals(List.copyOf(expectedKey.entrySet()));
    }

    private boolean sameKeyFields(Document index, Document expectedKey) {
        Object rawKey = index.get("key");
        if (!(rawKey instanceof Document key)) return false;
        return key.keySet().equals(expectedKey.keySet());
    }

    private String indexName(Document index) {
        return index.getString("name");
    }

    private Document positionKey() {
        return new Document(PARTITION_KEY, 1).append(CHAIN_POSITION, 1);
    }

    private Document requestIdKey() {
        return new Document(REQUEST_ID, 1);
    }

    private Document positionPartialFilter() {
        return new Document(PARTITION_KEY, new Document("$exists", true))
                .append(CHAIN_POSITION, new Document("$exists", true));
    }

    private Document requestIdPartialFilter() {
        return new Document(REQUEST_ID, new Document("$type", "string"));
    }

    private boolean hasDuplicateValues(MongoCollection<Document> collection, String field, boolean partitionPosition) {
        org.bson.conversions.Bson valid = partitionPosition
                ? Filters.and(Filters.exists(PARTITION_KEY), Filters.exists(field))
                : Filters.type(field, BsonType.STRING);
        List<org.bson.conversions.Bson> pipeline = new java.util.ArrayList<>();
        pipeline.add(Aggregates.match(valid));
        if (partitionPosition) {
            pipeline.add(Aggregates.unwind("$" + PARTITION_KEY));
            pipeline.add(Aggregates.unwind("$" + field));
        } else {
            pipeline.add(Aggregates.unwind("$" + REQUEST_ID));
            pipeline.add(Aggregates.match(Filters.type(REQUEST_ID, BsonType.STRING)));
        }
        Object groupKey = partitionPosition
                ? new Document("partition", "$" + PARTITION_KEY).append("value", "$" + field)
                : "$" + field;
        pipeline.add(Aggregates.group(groupKey, Accumulators.addToSet("documents", "$_id")));
        pipeline.add(Aggregates.match(Filters.expr(new Document("$gt", List.of(
                new Document("$size", "$documents"), 1
        )))));
        pipeline.add(Aggregates.limit(1));
        pipeline.add(Aggregates.project(Projections.include("_id", "documents")));
        Document duplicate = collection.aggregate(pipeline).first();
        return duplicate != null;
    }

    public enum IndexState { PRESENT, MISSING, LEGACY_SPARSE, INCOMPATIBLE, DUPLICATE_DATA }

    public record IndexCheck(String collection, String purpose, String expectedName, IndexState state,
            String observedName, boolean duplicatesDetected, String detail) {
        public String diagnostic() {
            return collection + "/" + expectedName + ": " + state + "; observed=" + observedName
                    + "; duplicatesDetected=" + duplicatesDetected + "; " + detail;
        }
    }

    public record IndexPreflight(List<IndexCheck> indexes) {
        public IndexPreflight {
            indexes = List.copyOf(indexes);
        }

        public boolean ready() {
            return indexes.stream().allMatch(check -> check.state() == IndexState.PRESENT);
        }

        public boolean safeToInitialize() {
            return indexes.stream().allMatch(check -> check.state() == IndexState.PRESENT
                    || check.state() == IndexState.MISSING && !check.duplicatesDetected());
        }

        public String diagnostic() {
            return String.join(" | ", indexes.stream().map(IndexCheck::diagnostic).toList());
        }
    }
}
