package com.frauddetection.trustauthority;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.trust-authority.audit", name = "sink", havingValue = "durable-hash-chain")
class DurableTrustAuthorityAuditSink implements TrustAuthorityAuditSink {

    private static final String COLLECTION = "trust_authority_audit_events";

    private final MongoTemplate mongoTemplate;
    private final TrustAuthorityMetrics metrics;

    DurableTrustAuthorityAuditSink(MongoTemplate mongoTemplate, TrustAuthorityMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
        mongoTemplate.indexOps(COLLECTION).ensureIndex(new Index().on("chain_position", Sort.Direction.ASC).unique());
        mongoTemplate.indexOps(COLLECTION).ensureIndex(new Index().on("event_id", Sort.Direction.ASC).unique());
        mongoTemplate.indexOps(COLLECTION).ensureIndex(new Index()
                .on("caller_service", Sort.Direction.ASC)
                .on("request_id", Sort.Direction.ASC));
    }

    @Override
    public synchronized void append(TrustAuthorityAuditEvent event) {
        try {
            insertChained(event);
            metrics.recordAuditWrite("SUCCESS");
        } catch (DuplicateKeyException conflict) {
            metrics.recordAuditAppendConflict();
            metrics.recordAuditAppendRetry();
            try {
                insertChained(event);
                metrics.recordAuditWrite("SUCCESS");
            } catch (DuplicateKeyException retryConflict) {
                metrics.recordAuditAppendConflict();
                metrics.recordAuditWrite("FAILURE");
                throw new TrustAuthorityAuditException("Trust authority audit event could not be persisted after append conflict.", retryConflict);
            } catch (DataAccessException exception) {
                metrics.recordAuditWrite("FAILURE");
                throw new TrustAuthorityAuditException("Trust authority audit event could not be persisted.", exception);
            }
        } catch (DataAccessException exception) {
            metrics.recordAuditWrite("FAILURE");
            throw new TrustAuthorityAuditException("Trust authority audit event could not be persisted.", exception);
        }
    }

    @Override
    public TrustAuthorityAuditIntegrityResponse integrity(int limit) {
        return integrity(limit, "WINDOW");
    }

    @Override
    public TrustAuthorityAuditIntegrityResponse integrity(int limit, String modeValue) {
        TrustAuthorityAuditIntegrityMode mode = TrustAuthorityAuditIntegrityMode.from(modeValue);
        try {
            Query query = new Query()
                    .limit(Math.max(1, Math.min(10_000, limit)));
            if (mode == TrustAuthorityAuditIntegrityMode.FULL_CHAIN) {
                query.with(Sort.by(Sort.Direction.ASC, "chain_position"));
            } else {
                query.with(Sort.by(Sort.Direction.DESC, "chain_position"));
            }
            List<TrustAuthorityAuditEvent> events = mongoTemplate.find(query, TrustAuthorityAuditEvent.class, COLLECTION).stream()
                    .sorted(Comparator.comparing(event -> event.chainPosition() == null ? Long.MAX_VALUE : event.chainPosition()))
                    .toList();
            return TrustAuthorityAuditIntegrityVerifier.verify(events, mode);
        } catch (DataAccessException exception) {
            return TrustAuthorityAuditIntegrityResponse.unavailable("AUDIT_STORE_UNAVAILABLE", mode);
        }
    }

    @Override
    public TrustAuthorityAuditHeadResponse head() {
        try {
            return TrustAuthorityAuditHeadResponse.from(latestEvent());
        } catch (DataAccessException exception) {
            throw new TrustAuthorityAuditException("Trust authority audit head could not be read.", exception);
        }
    }

    @Override
    public boolean requestSeen(String callerService, String requestId) {
        Query query = new Query(Criteria.where("caller_service").is(callerService).and("request_id").is(requestId))
                .limit(1);
        return mongoTemplate.exists(query, TrustAuthorityAuditEvent.class, COLLECTION);
    }

    private void insertChained(TrustAuthorityAuditEvent event) {
        TrustAuthorityAuditEvent latest = latestEvent();
        long nextPosition = latest == null || latest.chainPosition() == null ? 1L : latest.chainPosition() + 1L;
        String previousHash = latest == null ? null : latest.eventHash();
        TrustAuthorityAuditEvent chained = event.withChain(
                previousHash,
                TrustAuthorityAuditHasher.hash(event, previousHash, nextPosition),
                nextPosition
        );
        mongoTemplate.insert(chained, COLLECTION);
    }

    private TrustAuthorityAuditEvent latestEvent() {
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "chain_position"))
                .limit(1);
        return mongoTemplate.findOne(query, TrustAuthorityAuditEvent.class, COLLECTION);
    }
}
