package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Repository
class ExternalAuditAnchorPublicationStatusRepository {

    private final MongoTemplate mongoTemplate;

    ExternalAuditAnchorPublicationStatusRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    void recordSuccess(AuditAnchorDocument anchor, Instant publishedAt, String sinkType) throws DataAccessException {
        Query query = query(anchor.anchorId());
        Update status = base(anchor, publishedAt)
                .set("external_published", true)
                .set("external_published_at", publishedAt)
                .set("external_sink_type", safe(sinkType))
                .inc("external_publish_attempts", 1)
                .unset("last_external_publish_failure_reason");
        mongoTemplate.upsert(query, status, ExternalAuditAnchorPublicationStatusDocument.class);
    }

    void recordFailure(AuditAnchorDocument anchor, Instant attemptedAt, String reason) throws DataAccessException {
        Query query = query(anchor.anchorId());
        Update status = base(anchor, attemptedAt)
                .set("external_published", false)
                .inc("external_publish_attempts", 1)
                .set("last_external_publish_failure_reason", safe(reason));
        mongoTemplate.upsert(query, status, ExternalAuditAnchorPublicationStatusDocument.class);
    }

    private Query query(String localAnchorId) {
        return new Query(Criteria.where("local_anchor_id").is(localAnchorId));
    }

    private Update base(AuditAnchorDocument anchor, Instant updatedAt) {
        return new Update()
                .setOnInsert("local_anchor_id", anchor.anchorId())
                .setOnInsert("partition_key", anchor.partitionKey())
                .setOnInsert("chain_position", anchor.chainPosition())
                .set("updated_at", updatedAt);
    }

    private String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }
}
