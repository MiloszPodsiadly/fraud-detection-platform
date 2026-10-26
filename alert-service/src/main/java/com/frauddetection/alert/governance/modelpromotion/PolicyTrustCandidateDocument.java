package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;

/** Complete canonical registry staged for independent review; never modified in place. */
@Document(PolicyTrustCandidateDocument.COLLECTION)
@Immutable
public record PolicyTrustCandidateDocument(@Id String id,
        PolicyTrustRegistrySnapshotService.Registry registry, String action,
        long baseGeneration, String baseSha256, long generation,
        String canonicalJson, String sha256, String semanticDiffJson,
        String reviewSummary, boolean affectsProduction) {
    public static final String COLLECTION = "model_policy_trust_candidates";
}
