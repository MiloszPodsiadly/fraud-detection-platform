package com.frauddetection.enricher.merchant;

import java.time.Instant;
import java.util.Objects;

/** Signed bounded cohort coverage claim; it is an issuer attestation, not proof of universal completeness. */
record MerchantCoverageAssertion(int assertionVersion,
                                 String sourceIssuer,
                                 String institution,
                                 String source,
                                 String sourceEpoch,
                                 String merchantGrantId,
                                 int merchantGrantVersion,
                                 String merchantPolicyVersion,
                                 int identityVersion,
                                 String coverageId,
                                 String feedId,
                                 String populationId,
                                 int populationDefinitionVersion,
                                 String populationDefinitionCommitment,
                                 String inclusionRulesCommitment,
                                 String exclusionRulesCommitment,
                                 long sequenceOrigin,
                                 long fromSequence,
                                 long throughSequence,
                                 Instant coverageStart,
                                 Instant watermark,
                                 long generation,
                                 String previousCoverageCommitment) {
    MerchantCoverageAssertion {
        if (assertionVersion != 1 || merchantGrantVersion < 1 || merchantGrantVersion > 65_535
                || identityVersion < 1 || identityVersion > 65_535
                || populationDefinitionVersion < 1 || populationDefinitionVersion > 65_535
                || sequenceOrigin != 1 || fromSequence < 1 || throughSequence < fromSequence || generation < 1) {
            throw malformed();
        }
        MerchantAssertionCodec.reference(sourceIssuer);
        MerchantAssertionCodec.reference(institution);
        MerchantAssertionCodec.reference(source);
        MerchantAssertionCodec.reference(sourceEpoch);
        MerchantAssertionCodec.reference(merchantGrantId);
        MerchantAssertionCodec.reference(merchantPolicyVersion);
        MerchantAssertionCodec.reference(coverageId);
        MerchantAssertionCodec.reference(feedId);
        MerchantAssertionCodec.reference(populationId);
        MerchantAssertionCodec.commitment(populationDefinitionCommitment);
        MerchantAssertionCodec.commitment(inclusionRulesCommitment);
        MerchantAssertionCodec.commitment(exclusionRulesCommitment);
        if (previousCoverageCommitment != null) {
            MerchantAssertionCodec.commitment(previousCoverageCommitment);
        }
        MerchantAssertionCodec.time(coverageStart);
        MerchantAssertionCodec.time(watermark);
        Objects.requireNonNull(coverageStart);
        Objects.requireNonNull(watermark);
        if (!coverageStart.isBefore(watermark) || (generation == 1) != (previousCoverageCommitment == null)) {
            throw malformed();
        }
    }

    @Override public String toString() { return "MerchantCoverageAssertion[redacted]"; }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("MERCHANT_ASSERTION_MALFORMED");
    }
}
