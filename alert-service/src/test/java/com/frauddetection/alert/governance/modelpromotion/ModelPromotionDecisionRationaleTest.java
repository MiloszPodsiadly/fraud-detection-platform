package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionDecisionRationaleTest {

    @Test
    void bindsBoundedContentToServerDerivedProposalReferenceAndDigest() {
        String content = "Independent review confirmed the emergency retirement rationale.";
        ModelPromotionDecisionRationale rationale = new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.proposalReference("proposal-1"),
                content,
                ModelPromotionDecisionRationale.sha256(content)
        );

        assertThat(rationale.referenceId()).isEqualTo("model-promotion:proposal:proposal-1");
        assertThat(rationale.content()).isEqualTo(content);
    }

    @Test
    void rejectsMismatchedDigestAndOversizedContent() {
        assertThatThrownBy(() -> new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.proposalReference("proposal-1"),
                "Rationale text",
                "0".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        String oversized = "x".repeat(4097);
        assertThatThrownBy(() -> new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.proposalReference("proposal-1"),
                oversized,
                ModelPromotionDecisionRationale.sha256(oversized)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum byte length");
    }
}
