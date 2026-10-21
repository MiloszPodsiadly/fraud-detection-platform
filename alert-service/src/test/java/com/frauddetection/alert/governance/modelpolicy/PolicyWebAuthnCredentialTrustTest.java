package com.frauddetection.alert.governance.modelpolicy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyWebAuthnCredentialTrustTest {
    @Test
    void operationalStatusDoesNotRewriteHistoricalReliance() {
        assertThat(credential(false, "ACTIVE").canSignNewAction()).isTrue();
        assertThat(credential(false, "ACTIVE").canSupportPublishedApproval()).isTrue();
        for (String status : new String[] {"REVOKED", "LOST"}) {
            assertThat(credential(true, status).canSignNewAction()).isFalse();
            assertThat(credential(true, status).canSupportPublishedApproval()).isTrue();
        }
        assertThat(credential(true, "COMPROMISED").canSignNewAction()).isFalse();
        assertThat(credential(true, "COMPROMISED").canSupportPublishedApproval()).isFalse();
        assertThat(credential(false, "UNKNOWN").canSupportPublishedApproval()).isFalse();
        assertThat(credential(true, "ACTIVE").canSupportPublishedApproval()).isFalse();
    }

    private PolicyWebAuthnCredential credential(boolean revoked, String status) {
        return new PolicyWebAuthnCredential("id", "actor", "issuer", "subject", "handle", "key",
                1, revoked, Instant.EPOCH, Instant.EPOCH, status, "grant", "grant-id", "operator", "case", "key");
    }
}
