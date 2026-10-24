package com.frauddetection.alert.governance.modelpromotion;

import java.util.regex.Pattern;

public record ModelPromotionDecisionReference(
        String decisionId,
        ProposalReference proposal,
        EvidenceBundleReference evidenceBundle,
        AssessmentReference assessment,
        ApprovalReference approval
) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public ModelPromotionDecisionReference {
        decisionId = requiredIdentifier(decisionId, "decisionId");
        if (proposal == null) {
            throw new IllegalArgumentException("proposal is required");
        }
        if (approval == null) {
            throw new IllegalArgumentException("approval is required");
        }
    }

    public boolean hasAssessment() {
        return assessment != null;
    }

    public boolean hasEvidenceBundle() {
        return evidenceBundle != null;
    }

    private static String requiredIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    public record ProposalReference(String proposalId) {
        public ProposalReference {
            proposalId = requiredIdentifier(proposalId, "proposalId");
        }
    }

    public record EvidenceBundleReference(String evidenceBundleId, String canonicalSha256) {
        public EvidenceBundleReference {
            evidenceBundleId = requiredIdentifier(evidenceBundleId, "evidenceBundleId");
            canonicalSha256 = requiredSha256(canonicalSha256, "evidenceBundleSha256");
        }
    }

    public record AssessmentReference(String assessmentId, String canonicalSha256) {
        public AssessmentReference {
            assessmentId = requiredIdentifier(assessmentId, "assessmentId");
            canonicalSha256 = requiredSha256(canonicalSha256, "assessmentSha256");
        }
    }

    public record ApprovalReference(String approvalId) {
        public ApprovalReference {
            approvalId = requiredIdentifier(approvalId, "approvalId");
        }
    }

    private static String requiredSha256(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }
}
