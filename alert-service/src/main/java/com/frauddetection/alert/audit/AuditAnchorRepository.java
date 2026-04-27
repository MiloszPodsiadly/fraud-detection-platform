package com.frauddetection.alert.audit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AuditAnchorRepository {

    private final MongoTemplate mongoTemplate;

    public AuditAnchorRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public AuditAnchorDocument insert(AuditAnchorDocument document) throws DataAccessException {
        return mongoTemplate.insert(document);
    }

    public Optional<AuditAnchorDocument> findLatestByPartitionKey(String partitionKey) throws DataAccessException {
        Query query = new Query(Criteria.where("partition_key").is(partitionKey))
                .with(Sort.by(Sort.Direction.DESC, "created_at"))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, AuditAnchorDocument.class));
    }

}
