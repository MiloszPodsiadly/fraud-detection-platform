package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = ModelPromotionStateDocument.COLLECTION)
public class ModelPromotionStateDocument {

    public static final String COLLECTION = "model_promotion_states";
    public static final String IDENTITY_INDEX = "model_promotion_identity_unique_idx";

    @Id
    private String aggregateKey;
    @Field("model_name")
    private String modelName;
    @Field("model_version")
    private String modelVersion;
    @Field("feature_contract_version")
    private String featureContractVersion;
    @Field("model_artifact_sha256")
    private String modelArtifactSha256;
    @Field("institutional_scope")
    private String institutionalScope;
    private ModelGovernanceEnvironment environment;
    @Field("intended_use")
    private ModelPromotionIntendedUse intendedUse;
    @Field("lifecycle_state")
    private ModelPromotionLifecycleState lifecycleState;
    private long revision;
    @Field("accepted_decision_id")
    private String acceptedDecisionId;
    @Field("created_at")
    private Instant createdAt;
    @Field("updated_at")
    private Instant updatedAt;

    static ModelPromotionStateDocument candidate(ModelPromotionRegistrationCommand command, Instant committedAt) {
        ModelPromotionStateDocument document = new ModelPromotionStateDocument();
        ExactModelArtifactIdentity artifact = command.artifactIdentity();
        ModelGovernanceScope scope = command.governanceScope();
        document.aggregateKey = ModelPromotionPersistenceKey.aggregate(artifact, scope, command.intendedUse());
        document.modelName = artifact.modelName();
        document.modelVersion = artifact.modelVersion();
        document.featureContractVersion = artifact.featureContractVersion();
        document.modelArtifactSha256 = artifact.modelArtifactSha256();
        document.institutionalScope = scope.institutionalScope();
        document.environment = scope.environment();
        document.intendedUse = command.intendedUse();
        document.lifecycleState = ModelPromotionLifecycleState.CANDIDATE;
        document.revision = 0;
        document.createdAt = committedAt;
        document.updatedAt = committedAt;
        return document;
    }

    ModelPromotionAggregate toAggregate() {
        return new ModelPromotionAggregate(
                new ExactModelArtifactIdentity(modelName, modelVersion, featureContractVersion, modelArtifactSha256),
                new ModelGovernanceScope(institutionalScope, environment),
                intendedUse,
                lifecycleState,
                revision,
                acceptedDecisionId
        );
    }

    public String getAggregateKey() { return aggregateKey; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public String getFeatureContractVersion() { return featureContractVersion; }
    public String getModelArtifactSha256() { return modelArtifactSha256; }
    public String getInstitutionalScope() { return institutionalScope; }
    public ModelGovernanceEnvironment getEnvironment() { return environment; }
    public ModelPromotionIntendedUse getIntendedUse() { return intendedUse; }
    public ModelPromotionLifecycleState getLifecycleState() { return lifecycleState; }
    public long getRevision() { return revision; }
    public String getAcceptedDecisionId() { return acceptedDecisionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
