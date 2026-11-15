package com.frauddetection.enricher.merchant;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantAssertionAnchorTest {
    private static final String COMMITMENT = "a".repeat(64);

    @Test
    void documentRoundTripPreservesExactBoundedEvidence() {
        MerchantAssertionAnchor anchor = new MerchantAssertionAnchor(MerchantAssertionVerifier.Role.OBSERVATION,
                COMMITMENT, new byte[] { '{', '}' }, new byte[] { 1, 2, 3 });

        assertThat(MerchantAssertionAnchor.fromDocument(anchor.toDocument(), MerchantAssertionVerifier.Role.OBSERVATION))
                .isEqualTo(anchor);
    }

    @Test
    void rejectsOversizedBase64BeforeDecodingIt() {
        Document document = new Document("version", 1).append("role", MerchantAssertionVerifier.Role.OBSERVATION.name())
                .append("commitment", COMMITMENT).append("assertion", "A".repeat(10_925))
                .append("proof", Base64.getEncoder().encodeToString(new byte[] { 1 }));

        assertThatThrownBy(() -> MerchantAssertionAnchor.fromDocument(document, MerchantAssertionVerifier.Role.OBSERVATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MERCHANT_ANCHOR_INVALID");
    }
}
