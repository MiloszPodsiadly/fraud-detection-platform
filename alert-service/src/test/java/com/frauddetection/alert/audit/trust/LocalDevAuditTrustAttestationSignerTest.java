package com.frauddetection.alert.audit.trust;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDevAuditTrustAttestationSignerTest {

    @Test
    void shouldVerifyOwnSignatureAndRejectTamperedPayload() {
        LocalDevAuditTrustAttestationSigner signer = new LocalDevAuditTrustAttestationSigner(
                "local-key",
                "secret".getBytes(StandardCharsets.UTF_8)
        );
        byte[] payload = "{\"trust_level\":\"INTERNAL_ONLY\"}".getBytes(StandardCharsets.UTF_8);
        AuditTrustAttestationSignature signature = signer.sign(payload).orElseThrow();

        assertThat(signer.verify(payload, signature.signature(), signature.keyId())).isTrue();
        assertThat(signer.verify("{\"trust_level\":\"SIGNED_ATTESTATION\"}".getBytes(StandardCharsets.UTF_8),
                signature.signature(),
                signature.keyId())).isFalse();
    }
}
