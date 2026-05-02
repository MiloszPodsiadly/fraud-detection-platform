package com.frauddetection.alert.regulated;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class MongoRegulatedMutationTransactionCapabilityProbe implements RegulatedMutationTransactionCapabilityProbe {

    private static final String COLLECTION = "regulated_mutation_transaction_probe";

    private final RegulatedMutationTransactionRunner transactionRunner;
    private final MongoTemplate mongoTemplate;

    MongoRegulatedMutationTransactionCapabilityProbe(
            RegulatedMutationTransactionRunner transactionRunner,
            MongoTemplate mongoTemplate
    ) {
        this.transactionRunner = transactionRunner;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void verify() {
        String commitProbeId = "commit-" + UUID.randomUUID();
        transactionRunner.runLocalCommit(() -> {
            mongoTemplate.insert(new Document("_id", commitProbeId).append("probe", true), COLLECTION);
            mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").is(commitProbeId)
            ), COLLECTION);
            return null;
        });

        String rollbackProbeId = "rollback-" + UUID.randomUUID();
        try {
            transactionRunner.runLocalCommit(() -> {
                mongoTemplate.insert(new Document("_id", rollbackProbeId).append("probe", true), COLLECTION);
                throw new ProbeRollbackSignal();
            });
        } catch (ProbeRollbackSignal ignored) {
            // Expected. The absence check below proves the write was rolled back.
        }
        boolean rollbackFailed = mongoTemplate.exists(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(rollbackProbeId)
        ), COLLECTION);
        if (rollbackFailed) {
            mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").is(rollbackProbeId)
            ), COLLECTION);
            throw new IllegalStateException("Mongo transaction rollback probe failed.");
        }
    }

    private static final class ProbeRollbackSignal extends RuntimeException {
    }
}
