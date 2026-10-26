package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = ModelPromotionChampionClaimDocument.COLLECTION)
@Immutable
public record ModelPromotionChampionClaimDocument(
        @Id String championKey,
        @Field("aggregate_key") String aggregateKey,
        @Field("institutional_scope") String institutionalScope,
        ModelGovernanceEnvironment environment,
        @Field("intended_use") ModelPromotionIntendedUse intendedUse,
        @Field("decision_id") String decisionId,
        @Field("claimed_at") Instant claimedAt
) {
    public static final String COLLECTION = "model_promotion_champion_claims";
}
