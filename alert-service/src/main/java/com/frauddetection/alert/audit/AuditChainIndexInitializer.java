package com.frauddetection.alert.audit;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class AuditChainIndexInitializer {

    private static final String PARTITION_KEY = "partition_key";
    private static final String CHAIN_POSITION = "chain_position";

    private final MongoTemplate mongoTemplate;

    AuditChainIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void ensureIndexes() {
        ensureUniquePositionIndex("audit_events", "audit_partition_chain_position_uidx_v2");
        ensureUniquePositionIndex("audit_chain_anchors", "audit_anchor_partition_chain_position_uidx_v2");
    }

    private void ensureUniquePositionIndex(String collectionName, String indexName) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
        for (Document index : collection.listIndexes(Document.class)) {
            if (!hasPartitionPositionKey(index)) {
                continue;
            }
            if (Boolean.TRUE.equals(index.getBoolean("unique"))) {
                return;
            }
            throw new IllegalStateException("Audit chain index exists but is not unique for collection " + collectionName);
        }
        collection.createIndex(
                Indexes.ascending(PARTITION_KEY, CHAIN_POSITION),
                new IndexOptions()
                        .name(indexName)
                        .unique(true)
                        .partialFilterExpression(Filters.and(
                                Filters.exists(PARTITION_KEY),
                                Filters.exists(CHAIN_POSITION)
                        ))
        );
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
