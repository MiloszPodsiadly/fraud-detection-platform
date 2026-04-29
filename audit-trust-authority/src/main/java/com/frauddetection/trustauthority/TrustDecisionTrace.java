package com.frauddetection.trustauthority;

import com.fasterxml.jackson.annotation.JsonProperty;

record TrustDecisionTrace(
        @JsonProperty("identity_verified")
        boolean identityVerified,

        @JsonProperty("signature_verified")
        boolean signatureVerified,

        @JsonProperty("chain_verified")
        String chainVerified,

        @JsonProperty("policy_applied")
        String policyApplied,

        @JsonProperty("final_status")
        String finalStatus
) {
}
