package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Repository
class ExternalAuditAnchorPublicationStatusRepository {

    private final MongoTemplate mongoTemplate;

    ExternalAuditAnchorPublicationStatusRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    void recordSuccess(
            AuditAnchorDocument anchor,
            Instant publishedAt,
            String sinkType,
            ExternalAnchorReference reference,
            ExternalImmutabilityLevel immutabilityLevel
    ) throws DataAccessException {
        Query query = query(anchor.anchorId());
        Update status = base(anchor, publishedAt)
                .set("external_published", true)
                .set("external_publication_status", ExternalAuditAnchor.STATUS_PUBLISHED)
                .set("external_published_at", publishedAt)
                .set("external_sink_type", safe(sinkType))
                .set("external_key", reference == null ? null : safe(reference.externalKey(), 300))
                .set("anchor_hash", reference == null ? null : safe(reference.anchorHash(), 128))
                .set("external_hash", reference == null ? null : safe(reference.externalHash(), 128))
                .set("external_reference_verified_at", reference == null ? null : reference.verifiedAt())
                .set("external_immutability_level", immutabilityLevel == null ? ExternalImmutabilityLevel.NONE.name() : immutabilityLevel.name())
                .inc("external_publish_attempts", 1)
                .unset("manifest_status")
                .unset("last_external_publish_failure_reason");
        mongoTemplate.upsert(query, status, ExternalAuditAnchorPublicationStatusDocument.class);
    }

    void recordPartial(
            AuditAnchorDocument anchor,
            Instant attemptedAt,
            String sinkType,
            ExternalAnchorReference reference,
            ExternalImmutabilityLevel immutabilityLevel,
            String reason,
            String manifestStatus
    ) throws DataAccessException {
        Query query = query(anchor.anchorId());
        Update status = base(anchor, attemptedAt)
                .set("external_published", false)
                .set("external_publication_status", ExternalAuditAnchor.STATUS_PARTIAL)
                .set("external_sink_type", safe(sinkType))
                .set("external_key", reference == null ? null : safe(reference.externalKey(), 300))
                .set("anchor_hash", reference == null ? null : safe(reference.anchorHash(), 128))
                .set("external_hash", reference == null ? null : safe(reference.externalHash(), 128))
                .set("external_reference_verified_at", reference == null ? null : reference.verifiedAt())
                .set("external_immutability_level", immutabilityLevel == null ? ExternalImmutabilityLevel.NONE.name() : immutabilityLevel.name())
                .set("manifest_status", safe(manifestStatus))
                .set("last_external_publish_failure_reason", safe(reason))
                .inc("external_publish_attempts", 1)
                .unset("external_published_at");
        mongoTemplate.upsert(query, status, ExternalAuditAnchorPublicationStatusDocument.class);
    }

    void recordFailure(AuditAnchorDocument anchor, Instant attemptedAt, String reason) throws DataAccessException {
        Query query = query(anchor.anchorId());
        Update status = base(anchor, attemptedAt)
                .set("external_published", false)
                .set("external_publication_status", "FAILED")
                .inc("external_publish_attempts", 1)
                .unset("external_published_at")
                .unset("external_sink_type")
                .unset("external_key")
                .unset("anchor_hash")
                .unset("external_hash")
                .unset("external_reference_verified_at")
                .unset("external_immutability_level")
                .unset("manifest_status")
                .set("last_external_publish_failure_reason", safe(reason));
        mongoTemplate.upsert(query, status, ExternalAuditAnchorPublicationStatusDocument.class);
    }

    List<ExternalAuditAnchorPublicationStatusDocument> findNotPublished(String partitionKey, int limit) throws DataAccessException {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Query query = new Query(Criteria.where("partition_key").is(partitionKey)
                .and("external_published").is(false))
                .with(Sort.by(Sort.Direction.ASC, "chain_position"))
                .limit(boundedLimit);
        return mongoTemplate.find(query, ExternalAuditAnchorPublicationStatusDocument.class);
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
        return safe(value, 80);
    }

    private String safe(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
