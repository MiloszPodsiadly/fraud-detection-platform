package com.frauddetection.alert.audit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class AuditEventRepository {

    private final MongoTemplate mongoTemplate;

    public AuditEventRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public AuditEventDocument insert(AuditEventDocument document) throws DataAccessException {
        return mongoTemplate.insert(document);
    }

    public Optional<AuditEventDocument> findLatestBySourceService(String sourceService) throws DataAccessException {
        Query query = new Query(Criteria.where("source_service").is(sourceService))
                .with(Sort.by(Sort.Direction.DESC, "created_at"))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, AuditEventDocument.class));
    }

    public List<AuditEventDocument> findIntegrityWindow(
            String sourceService,
            Instant from,
            Instant to,
            int limit
    ) throws DataAccessException {
        List<Criteria> filters = new ArrayList<>();
        if (sourceService != null) {
            filters.add(Criteria.where("source_service").is(sourceService));
        }
        if (from != null && to != null) {
            filters.add(Criteria.where("created_at").gte(from).lte(to));
        } else if (from != null) {
            filters.add(Criteria.where("created_at").gte(from));
        } else if (to != null) {
            filters.add(Criteria.where("created_at").lte(to));
        }

        Query query = new Query()
                .with(Sort.by(Sort.Direction.ASC, "created_at"))
                .limit(limit);
        if (!filters.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(filters));
        }
        return mongoTemplate.find(query, AuditEventDocument.class);
    }
}
