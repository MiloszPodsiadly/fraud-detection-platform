package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
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

    private final MongoTemplate mongoTemplate;
    private final AuditEventQueryParser queryParser;
    private final AlertServiceMetrics metrics;

    public AuditEventReadService(
            MongoTemplate mongoTemplate,
            AuditEventQueryParser queryParser,
            AlertServiceMetrics metrics
    ) {
        this.mongoTemplate = mongoTemplate;
        this.queryParser = queryParser;
        this.metrics = metrics;
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
            return AuditEventReadResponse.available(auditQuery.limit(), documents);
        } catch (DataAccessException exception) {
            metrics.recordPlatformAuditReadRequest("UNAVAILABLE");
            return AuditEventReadResponse.unavailable(auditQuery.limit());
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
}
