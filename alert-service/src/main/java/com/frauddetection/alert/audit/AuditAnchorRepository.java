package com.frauddetection.alert.audit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

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
                .with(Sort.by(Sort.Order.desc("chain_position"), Sort.Order.desc("created_at")))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, AuditAnchorDocument.class));
    }

    public List<AuditAnchorDocument> findHeadWindow(String partitionKey, int limit) throws DataAccessException {
        Query query = new Query(Criteria.where("partition_key").is(partitionKey))
                .with(Sort.by(Sort.Direction.DESC, "chain_position"))
                .limit(limit);
        List<AuditAnchorDocument> newestFirst = mongoTemplate.find(query, AuditAnchorDocument.class);
        return newestFirst.reversed();
    }

    public List<AuditAnchorDocument> findByPartitionKeyAndChainPositionBetween(
            String partitionKey,
            long fromChainPosition,
            long toChainPosition,
            int limit
    ) throws DataAccessException {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("partition_key").is(partitionKey),
                Criteria.where("chain_position").gte(fromChainPosition).lte(toChainPosition)
        ))
                .with(Sort.by(Sort.Direction.ASC, "chain_position"))
                .limit(limit);
        return mongoTemplate.find(query, AuditAnchorDocument.class);
    }

    public List<AuditAnchorDocument> findByPartitionKeyAndChainPositionGreaterThan(
            String partitionKey,
            long chainPosition,
            int limit
    ) throws DataAccessException {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("partition_key").is(partitionKey),
                Criteria.where("chain_position").gt(chainPosition)
        ))
                .with(Sort.by(Sort.Direction.ASC, "chain_position"))
                .limit(limit);
        return mongoTemplate.find(query, AuditAnchorDocument.class);
    }

}
