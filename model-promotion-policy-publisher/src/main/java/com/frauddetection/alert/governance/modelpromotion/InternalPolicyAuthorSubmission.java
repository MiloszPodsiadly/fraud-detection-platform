package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

/** Portable review hand-off: exact draft plus the author's signed digest statement. */
record InternalPolicyAuthorSubmission(
        ModelPromotionPolicyPublisher.Draft policy,
        InternalPolicyApprovalArtifact.AuthorStatement authorStatement,
        String authorSignature
) {
    InternalPolicyAuthorSubmission {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(authorStatement);
        if (authorSignature == null || authorSignature.isBlank()) {
            throw new IllegalArgumentException("Author signature is required");
        }
    }
}
