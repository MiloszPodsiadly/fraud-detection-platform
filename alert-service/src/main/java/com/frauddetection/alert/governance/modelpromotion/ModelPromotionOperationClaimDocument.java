package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = ModelPromotionOperationClaimDocument.COLLECTION)
@Immutable
record ModelPromotionOperationClaimDocument(
        @Id String operationKey,
        @Field("aggregate_key") String aggregateKey,
        @Field("operation_id") String operationId,
        @Field("operation_type") OperationType operationType
) {
    static final String COLLECTION = "model_promotion_operation_claims";

    enum OperationType {
        REGISTER_CANDIDATE,
        SUBMIT_PROPOSAL,
        REVIEW_PROPOSAL
    }
}
