package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnChallenge;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnEvidence;

import java.util.Objects;

/** Original public WebAuthn ceremony evidence retained with publication. */
record OnlinePolicyApprovalArtifact(String publicationRequestId, ModelPromotionPolicyDraft draft,
        String draftSha256, ActionProof submission, ActionProof approval) {
    static final String VERSION = "model-promotion-online-approval-v1";

    OnlinePolicyApprovalArtifact {
        Objects.requireNonNull(publicationRequestId);
        Objects.requireNonNull(draft);
        Objects.requireNonNull(draftSha256);
        Objects.requireNonNull(submission);
        Objects.requireNonNull(approval);
    }

    record ActionProof(PolicyWebAuthnChallenge challenge, PolicyWebAuthnEvidence evidence,
            String publicKeyCose, String userHandle) {
        ActionProof {
            Objects.requireNonNull(challenge);
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(publicKeyCose);
            Objects.requireNonNull(userHandle);
        }
    }
}
