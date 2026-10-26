package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.idempotency.IdempotencyCanonicalHasher;

import java.util.List;

final class ModelPromotionPersistenceKey {

    private ModelPromotionPersistenceKey() {
    }

    static String aggregate(
            ExactModelArtifactIdentity artifact,
            ModelGovernanceScope scope,
            ModelPromotionIntendedUse intendedUse
    ) {
        return IdempotencyCanonicalHasher.hash(List.of(
                artifact.modelName(),
                artifact.modelVersion(),
                artifact.featureContractVersion(),
                artifact.modelArtifactSha256(),
                scope.institutionalScope(),
                scope.environment().name(),
                intendedUse.name()
        ));
    }

    static String champion(ModelGovernanceScope scope, ModelPromotionIntendedUse intendedUse) {
        return IdempotencyCanonicalHasher.hash(List.of(
                scope.institutionalScope(), scope.environment().name(), intendedUse.name()
        ));
    }

    static String operation(String aggregateKey, String operationId) {
        return IdempotencyCanonicalHasher.hash(List.of(aggregateKey, operationId));
    }
}
