package com.frauddetection.trustauthority;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.trust-authority.audit", name = "sink", havingValue = "durable-append-only")
class DurableTrustAuthorityAuditSink implements TrustAuthorityAuditSink {

    private static final String COLLECTION = "trust_authority_audit_events";

    private final MongoTemplate mongoTemplate;
    private final TrustAuthorityMetrics metrics;

    DurableTrustAuthorityAuditSink(MongoTemplate mongoTemplate, TrustAuthorityMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        mongoTemplate.indexOps(COLLECTION).ensureIndex(new Index().on("chain_position", Sort.Direction.ASC).unique());
        mongoTemplate.indexOps(COLLECTION).ensureIndex(new Index().on("event_id", Sort.Direction.ASC).unique());
    }

    @Override
    public synchronized void append(TrustAuthorityAuditEvent event) {
        try {
            TrustAuthorityAuditEvent latest = latestEvent();
            long nextPosition = latest == null || latest.chainPosition() == null ? 1L : latest.chainPosition() + 1L;
            String previousHash = latest == null ? null : latest.eventHash();
            TrustAuthorityAuditEvent chained = event.withChain(
                    previousHash,
                    TrustAuthorityAuditHasher.hash(event, previousHash, nextPosition),
                    nextPosition
            );
            mongoTemplate.insert(chained, COLLECTION);
            metrics.recordAuditWrite("SUCCESS");
        } catch (DataAccessException exception) {
            metrics.recordAuditWrite("FAILURE");
            throw new TrustAuthorityAuditException("Trust authority audit event could not be persisted.", exception);
        }
    }

    @Override
    public TrustAuthorityAuditIntegrityResponse integrity(int limit) {
        try {
            Query query = new Query()
                    .with(Sort.by(Sort.Direction.DESC, "chain_position"))
                    .limit(Math.max(1, Math.min(10_000, limit)));
            List<TrustAuthorityAuditEvent> events = mongoTemplate.find(query, TrustAuthorityAuditEvent.class, COLLECTION).stream()
                    .sorted(Comparator.comparing(event -> event.chainPosition() == null ? Long.MAX_VALUE : event.chainPosition()))
                    .toList();
            return TrustAuthorityAuditIntegrityVerifier.verify(events);
        } catch (DataAccessException exception) {
            return TrustAuthorityAuditIntegrityResponse.unavailable("AUDIT_STORE_UNAVAILABLE");
        }
    }

    private TrustAuthorityAuditEvent latestEvent() {
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "chain_position"))
                .limit(1);
        return mongoTemplate.findOne(query, TrustAuthorityAuditEvent.class, COLLECTION);
    }
}
