package com.frauddetection.alert.audit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Accumulators;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bson.BsonType;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

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
    void ensureIndexes() {
        ensureUniquePositionIndex("audit_events", "audit_partition_chain_position_uidx_v2");
        ensureUniquePositionIndex("audit_chain_anchors", "audit_anchor_partition_chain_position_uidx_v2");
        ensureUniqueRequestIdIndex();
    }

    public boolean hasRequiredUniqueIndexes() {
        return hasUniquePositionIndex("audit_events")
                && hasUniquePositionIndex("audit_chain_anchors")
                && hasUniqueRequestIdIndex();
    }

    private void ensureUniquePositionIndex(String collectionName, String indexName) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
        for (Document index : collection.listIndexes(Document.class)) {
            if (!hasPartitionPositionKey(index)) {
                continue;
            }
            if (hasRequiredPositionIndexOptions(index)) {
                return;
            }
            throw new IllegalStateException("Audit chain index exists but has incompatible uniqueness options for collection " + collectionName);
        }
        if (hasDuplicateValues(collection, CHAIN_POSITION, true)) {
            throw new IllegalStateException("Duplicate audit chain positions exist in collection " + collectionName
                    + "; refusing to create a unique index over incompatible durable evidence.");
        }
        collection.createIndex(
                Indexes.ascending(PARTITION_KEY, CHAIN_POSITION),
                new IndexOptions()
                        .name(indexName)
                        .unique(true)
                        .partialFilterExpression(positionPartialFilter())
        );
    }

    private void ensureUniqueRequestIdIndex() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("audit_events");
        for (Document index : collection.listIndexes(Document.class)) {
            if (!hasSingleFieldKey(index, REQUEST_ID)) {
                continue;
            }
            if (hasRequiredRequestIdIndexOptions(index)) {
                return;
            }
            throw new IllegalStateException("Audit request id index exists but has incompatible uniqueness options.");
        }
        if (hasDuplicateValues(collection, REQUEST_ID, false)) {
            throw new IllegalStateException("Duplicate audit request ids exist; refusing to create a unique index.");
        }
        collection.createIndex(
                Indexes.ascending(REQUEST_ID),
                new IndexOptions()
                        .name("audit_request_id_uidx_v2")
                        .unique(true)
                        .partialFilterExpression(requestIdPartialFilter())
        );
    }

    private boolean hasDuplicateValues(MongoCollection<Document> collection, String field, boolean requirePartition) {
        org.bson.conversions.Bson valid = requirePartition
                ? Filters.and(Filters.exists(PARTITION_KEY), Filters.exists(field))
                : Filters.type(field, BsonType.STRING);
        Document duplicate = collection.aggregate(java.util.List.of(
                Aggregates.match(valid),
                Aggregates.group(new Document("partition", requirePartition ? "$" + PARTITION_KEY : null)
                                .append("value", "$" + field),
                        Accumulators.sum("count", 1)),
                Aggregates.match(Filters.gt("count", 1)),
                Aggregates.limit(1),
                Aggregates.project(Projections.include("_id", "count"))
        )).first();
        return duplicate != null;
    }

    private boolean hasUniquePositionIndex(String collectionName) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
        for (Document index : collection.listIndexes(Document.class)) {
            if (hasPartitionPositionKey(index) && hasRequiredPositionIndexOptions(index)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUniqueRequestIdIndex() {
        for (Document index : mongoTemplate.getCollection("audit_events").listIndexes(Document.class)) {
            if (hasSingleFieldKey(index, REQUEST_ID) && hasRequiredRequestIdIndexOptions(index)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSingleFieldKey(Document index, String field) {
        Object rawKey = index.get("key");
        if (!(rawKey instanceof Document key)) {
            return false;
        }
        return Integer.valueOf(1).equals(key.get(field)) && key.size() == 1;
    }

    private boolean hasRequiredPositionIndexOptions(Document index) {
        return Boolean.TRUE.equals(index.getBoolean("unique"))
                && positionPartialFilter().equals(index.get("partialFilterExpression"));
    }

    private boolean hasRequiredRequestIdIndexOptions(Document index) {
        return Boolean.TRUE.equals(index.getBoolean("unique"))
                && requestIdPartialFilter().equals(index.get("partialFilterExpression"));
    }

    private Document positionPartialFilter() {
        return new Document(PARTITION_KEY, new Document("$exists", true))
                .append(CHAIN_POSITION, new Document("$exists", true));
    }

    private Document requestIdPartialFilter() {
        return new Document(REQUEST_ID, new Document("$type", "string"));
    }

    private boolean hasPartitionPositionKey(Document index) {
        Object rawKey = index.get("key");
        if (!(rawKey instanceof Document key)) {
            return false;
        }
        return Integer.valueOf(1).equals(key.get(PARTITION_KEY))
                && Integer.valueOf(1).equals(key.get(CHAIN_POSITION))
                && key.size() == 2;
    }
}
