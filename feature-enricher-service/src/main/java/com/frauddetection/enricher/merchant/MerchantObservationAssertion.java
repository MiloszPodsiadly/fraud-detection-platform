package com.frauddetection.enricher.merchant;

import java.time.Instant;
import java.util.Objects;

/** Signed Merchant-domain claims. Construction is structural only and never grants admission. */
record MerchantObservationAssertion(int assertionVersion,
                                    String sourceIssuer,
                                    String institution,
                                    String source,
                                    String sourceEpoch,
                                    String merchantGrantId,
                                    int merchantGrantVersion,
                                    String merchantPolicyVersion,
                                    int identityVersion,
                                    String sourceEventId,
                                    String transactionCommitment,
                                    String occurrenceCommitment,
                                    String receiptId,
                                    String rawCommitment,
                                    String enrichedCommitment,
                                    String merchantPseudonym,
                                    int mappingVersion,
                                    String mappingCommitment,
                                    String feedId,
                                    String populationId,
                                    int populationDefinitionVersion,
                                    String populationDefinitionCommitment,
                                    long sequence,
                                    Instant eventTime,
                                    Instant sourceObservedAt) {
    MerchantObservationAssertion {
        if (assertionVersion != 1 || merchantGrantVersion < 1 || merchantGrantVersion > 65_535
                || identityVersion < 1 || identityVersion > 65_535
                || mappingVersion < 1 || mappingVersion > 65_535
                || populationDefinitionVersion < 1 || populationDefinitionVersion > 65_535 || sequence < 1) {
            throw malformed();
        }
        MerchantAssertionCodec.reference(sourceIssuer);
        MerchantAssertionCodec.reference(institution);
        MerchantAssertionCodec.reference(source);
        MerchantAssertionCodec.reference(sourceEpoch);
        MerchantAssertionCodec.reference(merchantGrantId);
        MerchantAssertionCodec.reference(merchantPolicyVersion);
        MerchantAssertionCodec.reference(sourceEventId);
        MerchantAssertionCodec.commitment(transactionCommitment);
        MerchantAssertionCodec.commitment(occurrenceCommitment);
        MerchantAssertionCodec.commitment(receiptId);
        MerchantAssertionCodec.commitment(rawCommitment);
        MerchantAssertionCodec.commitment(enrichedCommitment);
        MerchantAssertionCodec.commitment(merchantPseudonym);
        MerchantAssertionCodec.commitment(mappingCommitment);
        MerchantAssertionCodec.reference(feedId);
        MerchantAssertionCodec.reference(populationId);
        MerchantAssertionCodec.commitment(populationDefinitionCommitment);
        MerchantAssertionCodec.time(eventTime);
        MerchantAssertionCodec.time(sourceObservedAt);
        Objects.requireNonNull(eventTime);
        Objects.requireNonNull(sourceObservedAt);
    }

    @Override public String toString() { return "MerchantObservationAssertion[redacted]"; }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("MERCHANT_ASSERTION_MALFORMED");
    }
}
