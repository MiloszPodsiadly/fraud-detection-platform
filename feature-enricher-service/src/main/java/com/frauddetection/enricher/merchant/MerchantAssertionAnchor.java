package com.frauddetection.enricher.merchant;

import org.bson.Document;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Exact bounded signed bytes retained for post-restart Merchant authority checks. */
final class MerchantAssertionAnchor {
    private static final int MAX_ASSERTION_BYTES = 8_192;
    private static final int MAX_PROOF_BYTES = 4_096;
    private final MerchantAssertionVerifier.Role role;
    private final String commitment;
    private final byte[] assertion;
    private final byte[] proof;

    MerchantAssertionAnchor(MerchantAssertionVerifier.Role role, String commitment, byte[] assertion, byte[] proof) {
        this.role = Objects.requireNonNull(role, "MERCHANT_ANCHOR_ROLE_REQUIRED");
        MerchantAssertionCodec.commitment(commitment);
        if (assertion == null || assertion.length == 0 || assertion.length > MAX_ASSERTION_BYTES
                || proof == null || proof.length == 0 || proof.length > MAX_PROOF_BYTES) {
            throw new IllegalArgumentException("MERCHANT_ANCHOR_INVALID");
        }
        this.commitment = commitment;
        this.assertion = assertion.clone();
        this.proof = proof.clone();
    }

    static MerchantAssertionAnchor observation(MerchantAssertionVerifier.VerifiedObservation verified) {
        Objects.requireNonNull(verified, "MERCHANT_OBSERVATION_EVIDENCE_REQUIRED");
        return new MerchantAssertionAnchor(MerchantAssertionVerifier.Role.OBSERVATION, verified.assertionCommitment(),
                verified.originalAssertionBytes(), verified.proofBytes());
    }

    static MerchantAssertionAnchor coverage(MerchantAssertionVerifier.VerifiedCoverage verified) {
        Objects.requireNonNull(verified, "MERCHANT_COVERAGE_EVIDENCE_REQUIRED");
        return new MerchantAssertionAnchor(MerchantAssertionVerifier.Role.COVERAGE, verified.assertionCommitment(),
                verified.originalAssertionBytes(), verified.proofBytes());
    }

    static MerchantAssertionAnchor fromDocument(Document document, MerchantAssertionVerifier.Role expectedRole) {
        if (document == null || !document.keySet().equals(java.util.Set.of("version", "role", "commitment", "assertion", "proof"))
                || !Integer.valueOf(1).equals(document.getInteger("version"))
                || !expectedRole.name().equals(document.getString("role"))) {
            throw new IllegalArgumentException("MERCHANT_ANCHOR_INVALID");
        }
        try {
            String assertionText = document.getString("assertion");
            String proofText = document.getString("proof");
            if (assertionText == null || assertionText.length() > 10_924
                    || proofText == null || proofText.length() > 5_464) {
                throw new IllegalArgumentException("MERCHANT_ANCHOR_INVALID");
            }
            byte[] assertion = Base64.getDecoder().decode(assertionText);
            byte[] proof = Base64.getDecoder().decode(proofText);
            if (!Base64.getEncoder().encodeToString(assertion).equals(assertionText)
                    || !Base64.getEncoder().encodeToString(proof).equals(proofText)) {
                throw new IllegalArgumentException("MERCHANT_ANCHOR_INVALID");
            }
            return new MerchantAssertionAnchor(expectedRole, document.getString("commitment"), assertion, proof);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("MERCHANT_ANCHOR_INVALID");
        }
    }

    Document toDocument() {
        return new Document("version", 1).append("role", role.name()).append("commitment", commitment)
                .append("assertion", Base64.getEncoder().encodeToString(assertion))
                .append("proof", Base64.getEncoder().encodeToString(proof));
    }

    MerchantAssertionVerifier.Role role() { return role; }
    String commitment() { return commitment; }
    byte[] assertionBytes() { return assertion.clone(); }
    byte[] proofBytes() { return proof.clone(); }

    @Override public boolean equals(Object other) {
        return other instanceof MerchantAssertionAnchor that && role == that.role
                && commitment.equals(that.commitment) && Arrays.equals(assertion, that.assertion)
                && Arrays.equals(proof, that.proof);
    }

    @Override public int hashCode() {
        int result = Objects.hash(role, commitment);
        result = 31 * result + Arrays.hashCode(assertion);
        return 31 * result + Arrays.hashCode(proof);
    }

    @Override public String toString() { return "MerchantAssertionAnchor[redacted]"; }
}
