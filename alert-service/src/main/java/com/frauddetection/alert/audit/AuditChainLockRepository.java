package com.frauddetection.alert.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Repository
public class AuditChainLockRepository {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public AuditChainLockRepository(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.systemUTC());
    }

    AuditChainLockRepository(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public AuditChainLockDocument acquire(String partitionKey, String ownerToken) throws DataAccessException {
        Instant now = clock.instant();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(partitionKey),
                new Criteria().orOperator(
                        Criteria.where("locked_until").exists(false),
                        Criteria.where("locked_until").lte(now)
                )
        ));
        Update update = new Update()
                .setOnInsert("_id", partitionKey)
                .set("owner_token", ownerToken)
                .set("locked_until", now.plus(LOCK_TTL));
        try {
            AuditChainLockDocument lock = mongoTemplate.findAndModify(
                    query,
                    update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    AuditChainLockDocument.class
            );
            if (lock == null || !ownerToken.equals(lock.ownerToken())) {
                throw new AuditChainConflictException("Audit chain partition is locked.");
            }
            return lock;
        } catch (DuplicateKeyException exception) {
            throw new AuditChainConflictException("Audit chain lock acquisition raced.");
        }
    }

    public void release(String partitionKey, String ownerToken) throws DataAccessException {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(partitionKey),
                Criteria.where("owner_token").is(ownerToken)
        ));
        Update update = new Update()
                .set("locked_until", Instant.EPOCH)
                .unset("owner_token");
        mongoTemplate.updateFirst(query, update, AuditChainLockDocument.class);
    }
}
