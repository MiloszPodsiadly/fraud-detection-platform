package com.frauddetection.alert.audit;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditEventCompensationLookup {

    private final MongoTemplate mongoTemplate;

    public AuditEventCompensationLookup(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<AuditEventDocument> findExternalAnchorAbortCompensations(List<AuditEventDocument> documents) {
        List<String> auditIds = documents.stream()
                .map(AuditEventDocument::auditId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (auditIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("action").is(AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED),
                Criteria.where("resource_type").is(AuditResourceType.AUDIT_EVENT),
                Criteria.where("resource_id").in(auditIds)
        ))
                .with(Sort.by(Sort.Direction.DESC, "created_at"))
                .limit(auditIds.size());
        return mongoTemplate.find(query, AuditEventDocument.class);
    }
}
