package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Two independently signed statements; neither a policy signature nor a Mongo approval claim. */
public record InternalPolicyApprovalArtifact(
        AuthorStatement authorStatement,
        String authorSignature,
        ApprovalDecision approvalDecision,
        String approverSignature
) {

    public static final String VERSION = "model-promotion-internal-approval-v1";
    public static final String AUTHOR_PURPOSE = "MODEL_PROMOTION_POLICY_AUTHOR_V1";
    public static final String APPROVER_PURPOSE = "MODEL_PROMOTION_POLICY_APPROVER_V1";
    static final String PUBLISHER_CHALLENGE_PURPOSE = "MODEL_PROMOTION_POLICY_PUBLISHER_CHALLENGE_V1";

    public InternalPolicyApprovalArtifact {
        Objects.requireNonNull(authorStatement, "authorStatement is required");
        Objects.requireNonNull(approvalDecision, "approvalDecision is required");
        required(authorSignature, "authorSignature");
        required(approverSignature, "approverSignature");
    }

    public static byte[] authorSigningBytes(ObjectMapper mapper, AuthorStatement statement) {
        return GovernanceCanonicalJson.serialize(mapper, mapper.valueToTree(Objects.requireNonNull(statement)));
    }

    public static byte[] approverSigningBytes(ObjectMapper mapper, ApprovalDecision decision) {
        return GovernanceCanonicalJson.serialize(mapper, mapper.valueToTree(Objects.requireNonNull(decision)));
    }

    public static String authorStatementSha256(ObjectMapper mapper, AuthorStatement statement) {
        return GovernanceCanonicalJson.sha256(authorSigningBytes(mapper, statement));
    }

    public static String proofSha256(ObjectMapper mapper, InternalPolicyApprovalArtifact artifact) {
        return GovernanceCanonicalJson.sha256(mapper, artifact);
    }

    public record AuthorStatement(
            String contractVersion,
            String purpose,
            String policyId,
            long revision,
            String policyCanonicalSha256,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            String effectiveFromInclusive,
            String effectiveToExclusive,
            String authorIdentity,
            String authorKeyId,
            String publisherIdentity,
            String publisherKeyId,
            String issuerIdentity,
            String issuerKeyId,
            String authoredAt
    ) {
        public AuthorStatement {
            if (!VERSION.equals(contractVersion) || !AUTHOR_PURPOSE.equals(purpose)) {
                throw new IllegalArgumentException("Unsupported author statement contract");
            }
            identifier(policyId, "policyId");
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
            sha256(policyCanonicalSha256);
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
            timestamp(effectiveFromInclusive, "effectiveFromInclusive");
            timestamp(effectiveToExclusive, "effectiveToExclusive");
            identifier(authorIdentity, "authorIdentity");
            identifier(authorKeyId, "authorKeyId");
            identifier(publisherIdentity, "publisherIdentity");
            identifier(publisherKeyId, "publisherKeyId");
            identifier(issuerIdentity, "issuerIdentity");
            identifier(issuerKeyId, "issuerKeyId");
            timestamp(authoredAt, "authoredAt");
        }
    }

    public record ApprovalDecision(
            String contractVersion,
            String purpose,
            String authorStatementSha256,
            String approverIdentity,
            String approverKeyId,
            Decision decision,
            String decidedAt,
            String expiresAt,
            String approvalReference
    ) {
        public ApprovalDecision {
            if (!VERSION.equals(contractVersion) || !APPROVER_PURPOSE.equals(purpose)) {
                throw new IllegalArgumentException("Unsupported approver statement contract");
            }
            sha256(authorStatementSha256);
            identifier(approverIdentity, "approverIdentity");
            identifier(approverKeyId, "approverKeyId");
            Objects.requireNonNull(decision, "decision is required");
            timestamp(decidedAt, "decidedAt");
            timestamp(expiresAt, "expiresAt");
            identifier(approvalReference, "approvalReference");
        }
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }

    private static void identifier(String value, String field) {
        ModelPromotionTrustedIssuerRegistry.identifier(value, field);
    }

    private static void timestamp(String value, String field) {
        ModelPromotionTrustedIssuerRegistry.canonicalTime(value, field);
    }

    private static void sha256(String value) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Approval digest must be lowercase SHA-256");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
