package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AuditEventReadService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventReadService.class);

    private final MongoTemplate mongoTemplate;
    private final AuditEventQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;

    public AuditEventReadService(
            MongoTemplate mongoTemplate,
            AuditEventQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService
    ) {
        this.mongoTemplate = mongoTemplate;
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
    }

    public AuditEventReadResponse readEvents(
            String eventType,
            String actorId,
            String resourceType,
            String resourceId,
            String from,
            String to,
            Integer limit
    ) {
        AuditEventQuery auditQuery = queryParser.parse(eventType, actorId, resourceType, resourceId, from, to, limit);
        try {
            List<AuditEventDocument> documents = mongoTemplate.find(toMongoQuery(auditQuery), AuditEventDocument.class);
            metrics.recordPlatformAuditReadRequest("AVAILABLE");
            AuditEventReadResponse response = AuditEventReadResponse.available(auditQuery.limit(), documents);
            auditEventsRead(auditQuery, response.count(), AuditOutcome.SUCCESS, null);
            return response;
        } catch (DataAccessException exception) {
            metrics.recordPlatformAuditReadRequest("UNAVAILABLE");
            AuditEventReadResponse response = AuditEventReadResponse.unavailable(auditQuery.limit());
            try {
                auditEventsRead(auditQuery, response.count(), AuditOutcome.FAILED, "AUDIT_STORE_UNAVAILABLE");
            } catch (AuditPersistenceUnavailableException ignored) {
                log.warn("Audit read access audit could not be persisted for audit events read.");
            }
            return response;
        }
    }

    private Query toMongoQuery(AuditEventQuery auditQuery) {
        List<Criteria> filters = new ArrayList<>();
        if (auditQuery.eventType() != null) {
            filters.add(Criteria.where("event_type").is(auditQuery.eventType()));
        }
        if (auditQuery.actorId() != null) {
            filters.add(Criteria.where("actor_id").is(auditQuery.actorId()));
        }
        if (auditQuery.resourceType() != null) {
            filters.add(Criteria.where("resource_type").is(auditQuery.resourceType()));
        }
        if (auditQuery.resourceId() != null) {
            filters.add(Criteria.where("resource_id").is(auditQuery.resourceId()));
        }
        if (auditQuery.from() != null && auditQuery.to() != null) {
            filters.add(Criteria.where("created_at").gte(auditQuery.from()).lte(auditQuery.to()));
        } else if (auditQuery.to() != null) {
            filters.add(Criteria.where("created_at").lte(auditQuery.to()));
        }

        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "created_at"))
                .limit(auditQuery.limit());
        if (!filters.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(filters));
        }
        return query;
    }

    private void auditEventsRead(
            AuditEventQuery auditQuery,
            int countReturned,
            AuditOutcome outcome,
            String failureReason
    ) {
        auditService.audit(
                AuditAction.READ_AUDIT_EVENTS,
                AuditResourceType.AUDIT_EVENT,
                null,
                null,
                "audit-events-reader",
                outcome,
                failureReason,
                AuditEventMetadataSummary.auditRead(
                        null,
                        "alert-service",
                        "1.0",
                        "GET /api/v1/audit/events",
                        filtersSummary(auditQuery),
                        countReturned
                )
        );
    }

    private String filtersSummary(AuditEventQuery auditQuery) {
        List<String> filters = new ArrayList<>();
        if (auditQuery.eventType() != null) {
            filters.add("event_type=" + auditQuery.eventType().name());
        }
        if (auditQuery.actorId() != null) {
            filters.add("actor_id=present");
        }
        if (auditQuery.resourceType() != null) {
            filters.add("resource_type=" + auditQuery.resourceType().name());
        }
        if (auditQuery.resourceId() != null) {
            filters.add("resource_id=present");
        }
        if (auditQuery.from() != null) {
            filters.add("from=present");
        }
        if (auditQuery.to() != null) {
            filters.add("to=present");
        }
        filters.add("limit=" + auditQuery.limit());
        return String.join(";", filters);
    }
}
