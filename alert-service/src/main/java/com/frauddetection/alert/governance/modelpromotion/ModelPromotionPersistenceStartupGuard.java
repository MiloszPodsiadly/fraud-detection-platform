package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.regulated.RegulatedMutationTransactionCapabilityProbe;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelPromotionPersistenceStartupGuard implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final RegulatedMutationTransactionCapabilityProbe transactionCapabilityProbe;
    private final boolean enabled;

    public ModelPromotionPersistenceStartupGuard(
            MongoTemplate mongoTemplate,
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectProvider<RegulatedMutationTransactionCapabilityProbe> transactionCapabilityProbe,
            @Value("${app.model-promotion.persistence.enabled:true}") boolean enabled
    ) {
        this.mongoTemplate = mongoTemplate;
        this.transactionRunner = transactionRunner;
        this.transactionCapabilityProbe = transactionCapabilityProbe.getIfAvailable();
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (transactionRunner.mode() != RegulatedMutationTransactionMode.REQUIRED) {
            throw failure("TRANSACTIONS_NOT_REQUIRED");
        }
        if (transactionCapabilityProbe == null) {
            throw failure("TRANSACTION_PROBE_MISSING");
        }
        transactionCapabilityProbe.verify();
        verifyIndex(
                ModelPromotionStateDocument.COLLECTION,
                ModelPromotionStateDocument.IDENTITY_INDEX,
                true,
                new Document("model_name", 1)
                        .append("model_version", 1)
                        .append("feature_contract_version", 1)
                        .append("model_artifact_sha256", 1)
                        .append("institutional_scope", 1)
                        .append("environment", 1)
                        .append("intended_use", 1)
        );
        verifyIndex(
                ModelPromotionDecisionDocument.COLLECTION,
                ModelPromotionDecisionDocument.OPERATION_INDEX,
                true,
                new Document("aggregate_key", 1).append("operation_id", 1)
        );
        verifyIndex(
                ModelPromotionDecisionDocument.COLLECTION,
                ModelPromotionDecisionDocument.DECISION_INDEX,
                true,
                new Document("decision_id", 1)
        );
        verifyIndex(
                ModelPromotionDecisionDocument.COLLECTION,
                ModelPromotionDecisionDocument.HISTORY_INDEX,
                false,
                new Document("aggregate_key", 1).append("committed_at", -1).append("operation_id", 1)
        );
        verifyIndex(
                ModelPromotionProposalDocument.COLLECTION,
                ModelPromotionProposalDocument.OPERATION_INDEX,
                true,
                new Document("aggregate_key", 1).append("operation_id", 1)
        );
        verifyIndex(
                ModelPromotionProposalDocument.COLLECTION,
                ModelPromotionProposalDocument.PENDING_REVIEW_INDEX,
                false,
                new Document("aggregate_key", 1)
                        .append("review_status", 1)
                        .append("submitted_at", -1)
        );
        verifyIndex(
                ModelPromotionProposalDocument.COLLECTION,
                ModelPromotionProposalDocument.HISTORY_INDEX,
                false,
                new Document("aggregate_key", 1).append("submitted_at", -1).append("_id", 1)
        );
        verifyIndex(
                ModelPromotionChampionClaimDocument.COLLECTION,
                "_id_",
                true,
                new Document("_id", 1)
        );
    }

    private void verifyIndex(String collectionName, String indexName, boolean unique, Document keys) {
        if (!mongoTemplate.collectionExists(collectionName)) {
            throw failure("COLLECTION_MISSING:" + collectionName);
        }
        List<Document> indexes = mongoTemplate.getCollection(collectionName).listIndexes().into(new java.util.ArrayList<>());
        Document index = indexes.stream()
                .filter(candidate -> indexName.equals(candidate.getString("name")))
                .findFirst()
                .orElseThrow(() -> failure("INDEX_MISSING:" + indexName));
        if (!keys.equals(index.get("key", Document.class))) {
            throw failure("INDEX_KEYS_INVALID:" + indexName);
        }
        boolean databaseEnforcesUnique = "_id_".equals(indexName)
                || Boolean.TRUE.equals(index.getBoolean("unique", false));
        if (unique != databaseEnforcesUnique) {
            throw failure("INDEX_UNIQUENESS_INVALID:" + indexName);
        }
    }

    private IllegalStateException failure(String reason) {
        return new IllegalStateException("Model promotion persistence startup guard failed: " + reason);
    }
}
