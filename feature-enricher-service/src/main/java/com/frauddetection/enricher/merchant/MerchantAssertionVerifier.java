package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.source.SourceReceiptIdentity;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageSeal;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Private Merchant assertion verifier. Verified values are capabilities, not source-lineage admission. */
final class MerchantAssertionVerifier {
    static final String OBSERVATION_PURPOSE = "MERCHANT_OBSERVATION_ASSERTION";
    static final String COVERAGE_PURPOSE = "MERCHANT_COVERAGE_ASSERTION";
    private static final Duration MAX_COVERAGE_WINDOW = Duration.ofDays(90);
    private static final Duration SOURCE_OBSERVED_EARLY_SKEW = Duration.ofMinutes(5);
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(2);
    private final SourceSignatureVerifier originVerifier;
    private final SourceSignatureVerifier assertionVerifier;
    private final MerchantAssertionTrustPolicy trustPolicy;
    private final Clock clock;
    private final Object verifierIdentity = new Object();

    MerchantAssertionVerifier(SourceVerificationProperties sourcePolicy,
                              List<MerchantAssertionTrustPolicy.Grant> grants,
                              Clock clock) {
        this.clock = Objects.requireNonNull(clock, "MERCHANT_ASSERTION_CLOCK_REQUIRED");
        this.trustPolicy = new MerchantAssertionTrustPolicy(grants);
        this.originVerifier = new SourceSignatureVerifier(Objects.requireNonNull(sourcePolicy), clock);
        this.assertionVerifier = new SourceSignatureVerifier(
                new SourceVerificationProperties(trustPolicy.keys()), clock);
    }

    Result verifyObservation(byte[] proof, byte[] originalAssertion, ExpectedSourceBinding expected) {
        if (!validOrigin(expected == null ? null : expected.origin())) { return Denied.SOURCE_NOT_AUTHORIZED; }
        byte[] payload = boundedAssertion(originalAssertion);
        byte[] proofBytes = boundedProof(proof);
        if (payload == null) { return Denied.PAYLOAD_REJECTED; }
        if (proofBytes == null) { return Denied.PROOF_REJECTED; }
        MerchantObservationAssertion claim;
        try { claim = MerchantAssertionCodec.parseObservation(payload); }
        catch (RuntimeException malformed) { return Denied.MALFORMED_ASSERTION; }
        SourceSignatureVerifier.Verified signature = signature(proofBytes, payload, Role.OBSERVATION);
        if (signature == null) { return Denied.SIGNATURE_OR_POLICY_DENIED; }
        MerchantAssertionTrustPolicy.Grant grant = trustPolicy.grant(Role.OBSERVATION, signature);
        if (grant == null) { return Denied.AUTHORITY_NOT_AUTHORIZED; }

        SourceScope source = expected.origin().scope();
        if (!matchesSource(claim.sourceIssuer(), claim.institution(), claim.source(), claim.sourceEpoch(), claim.identityVersion(),
                grant.sourceIdentity())
                || !claim.sourceIssuer().equals(source.issuer()) || !claim.institution().equals(source.institution())
                || !claim.source().equals(source.source()) || !claim.sourceEpoch().equals(source.sourceEpoch())
                || !claim.sourceEventId().equals(source.sourceEventId()) || !claim.sourceEventId().equals(signature.scope().sourceEventId())
                || !claim.eventTime().equals(source.eventTime()) || !claim.eventTime().equals(signature.scope().eventTime())) {
            return Denied.SOURCE_BINDING_MISMATCH;
        }
        if (!claim.merchantGrantId().equals(grant.merchantGrantId())
                || claim.merchantGrantVersion() != grant.merchantGrantVersion()
                || !claim.merchantPolicyVersion().equals(grant.key().policyVersion())) {
            return Denied.AUTHORITY_NOT_AUTHORIZED;
        }
        String expectedReceipt;
        try { expectedReceipt = SourceReceiptIdentity.identity(source); }
        catch (RuntimeException invalidOrigin) { return Denied.SOURCE_NOT_AUTHORIZED; }
        if (!claim.receiptId().equals(expectedReceipt) || !claim.receiptId().equals(expected.receiptId())
                || !claim.rawCommitment().equals(expected.rawCommitment())
                || !claim.enrichedCommitment().equals(expected.enrichedCommitment())
                || !claim.transactionCommitment().equals(expected.transactionCommitment())
                || !claim.occurrenceCommitment().equals(expected.occurrenceCommitment())) {
            return Denied.SOURCE_BINDING_MISMATCH;
        }
        if (!matchesPopulation(claim.feedId(), claim.populationId(), claim.populationDefinitionVersion(),
                claim.populationDefinitionCommitment(), grant.population())) {
            return Denied.POPULATION_NOT_AUTHORIZED;
        }
        MerchantAssertionTrustPolicy.MerchantMapping mapping = grant.mapping();
        if (mapping == null || claim.mappingVersion() != mapping.version()
                || !claim.mappingCommitment().equals(mapping.commitment())
                || claim.mappingVersion() != expected.mappingVersion()
                || !claim.mappingCommitment().equals(expected.mappingCommitment())
                || !claim.merchantPseudonym().equals(expected.merchantPseudonym())) {
            return Denied.MAPPING_NOT_AUTHORIZED;
        }
        if (!claim.sourceObservedAt().isBefore(claim.eventTime().minus(SOURCE_OBSERVED_EARLY_SKEW))
                && !claim.sourceObservedAt().isAfter(clock.instant().plus(FUTURE_SKEW))
                && !claim.eventTime().isAfter(clock.instant().plus(FUTURE_SKEW))) {
            return new VerifiedObservation(verifierIdentity, claim, signature, expected.origin(), grant,
                    payload, proofBytes, assertionCommitment(Role.OBSERVATION, signature, payload));
        }
        return Denied.TIME_NOT_AUTHORIZED;
    }

    Result verifyCoverage(byte[] proof, byte[] originalAssertion, SourceSignatureVerifier.Verified origin,
                          VerifiedCoverage previous) {
        if (!validOrigin(origin)) { return Denied.SOURCE_NOT_AUTHORIZED; }
        byte[] payload = boundedAssertion(originalAssertion);
        byte[] proofBytes = boundedProof(proof);
        if (payload == null) { return Denied.PAYLOAD_REJECTED; }
        if (proofBytes == null) { return Denied.PROOF_REJECTED; }
        MerchantCoverageAssertion claim;
        try { claim = MerchantAssertionCodec.parseCoverage(payload); }
        catch (RuntimeException malformed) { return Denied.MALFORMED_ASSERTION; }
        SourceSignatureVerifier.Verified signature = signature(proofBytes, payload, Role.COVERAGE);
        if (signature == null) { return Denied.SIGNATURE_OR_POLICY_DENIED; }
        MerchantAssertionTrustPolicy.Grant grant = trustPolicy.grant(Role.COVERAGE, signature);
        if (grant == null) { return Denied.AUTHORITY_NOT_AUTHORIZED; }

        SourceScope source = origin.scope();
        if (!matchesSource(claim.sourceIssuer(), claim.institution(), claim.source(), claim.sourceEpoch(), claim.identityVersion(),
                grant.sourceIdentity())
                || !claim.sourceIssuer().equals(source.issuer()) || !claim.institution().equals(source.institution())
                || !claim.source().equals(source.source()) || !claim.sourceEpoch().equals(source.sourceEpoch())
                || !claim.coverageId().equals(signature.scope().sourceEventId())
                || !claim.watermark().equals(signature.scope().eventTime())) {
            return Denied.SOURCE_BINDING_MISMATCH;
        }
        if (!claim.merchantGrantId().equals(grant.merchantGrantId())
                || claim.merchantGrantVersion() != grant.merchantGrantVersion()
                || !claim.merchantPolicyVersion().equals(grant.key().policyVersion())) {
            return Denied.AUTHORITY_NOT_AUTHORIZED;
        }
        MerchantAssertionTrustPolicy.Population population = grant.population();
        if (!matchesPopulation(claim.feedId(), claim.populationId(), claim.populationDefinitionVersion(),
                claim.populationDefinitionCommitment(), population)
                || !claim.inclusionRulesCommitment().equals(population.inclusionRulesCommitment())
                || !claim.exclusionRulesCommitment().equals(population.exclusionRulesCommitment())
                || claim.sequenceOrigin() != 1 || claim.coverageStart().isBefore(population.earliestCoverageStart())
                || Duration.between(claim.coverageStart(), claim.watermark()).compareTo(MAX_COVERAGE_WINDOW) > 0
                || claim.watermark().isAfter(clock.instant().plus(FUTURE_SKEW))) {
            return Denied.POPULATION_NOT_AUTHORIZED;
        }
        if (!validCoverageChain(claim, previous)) { return Denied.COVERAGE_CHAIN_MISMATCH; }
        return new VerifiedCoverage(verifierIdentity, claim, signature, origin, grant, payload, proofBytes,
                assertionCommitment(Role.COVERAGE, signature, payload));
    }

    /** Revalidates a retained signed member against the current durable source receipt and store identity. */
    boolean verifyStoredObservation(MerchantAssertionAnchor anchor, SourceLineage lineage, Observation stored,
                                   MerchantPseudonyms pseudonyms) {
        if (anchor == null || anchor.role() != Role.OBSERVATION || lineage == null
                || lineage.state() != SourceLineage.State.VERIFIED || lineage.source() == null
                || stored == null || pseudonyms == null) { return false; }
        byte[] payload = boundedAssertion(anchor.assertionBytes());
        byte[] proof = boundedProof(anchor.proofBytes());
        if (payload == null || proof == null) { return false; }
        MerchantObservationAssertion claim;
        try { claim = MerchantAssertionCodec.parseObservation(payload); }
        catch (RuntimeException malformed) { return false; }
        SourceSignatureVerifier.Verified signature = signature(proof, payload, Role.OBSERVATION);
        MerchantAssertionTrustPolicy.Grant grant = trustPolicy.grant(Role.OBSERVATION, signature);
        SourceScope source = lineage.source();
        String commitment = signature == null ? null : assertionCommitment(Role.OBSERVATION, signature, payload);
        if (grant == null || !Objects.equals(commitment, anchor.commitment())
                || !matchesSource(claim.sourceIssuer(), claim.institution(), claim.source(), claim.sourceEpoch(), claim.identityVersion(), grant.sourceIdentity())
                || !claim.sourceIssuer().equals(source.issuer()) || !claim.institution().equals(source.institution())
                || !claim.source().equals(source.source()) || !claim.sourceEpoch().equals(source.sourceEpoch())
                || !claim.sourceEventId().equals(source.sourceEventId()) || !claim.eventTime().equals(source.eventTime())
                || !claim.sourceEventId().equals(signature.scope().sourceEventId())
                || !claim.eventTime().equals(signature.scope().eventTime())
                || !claim.receiptId().equals(lineage.receiptId()) || !claim.enrichedCommitment().equals(lineage.eventCommitment())
                || !claim.merchantGrantId().equals(grant.merchantGrantId())
                || claim.merchantGrantVersion() != grant.merchantGrantVersion()
                || !claim.merchantPolicyVersion().equals(grant.key().policyVersion())
                || !matchesPopulation(claim.feedId(), claim.populationId(), claim.populationDefinitionVersion(),
                claim.populationDefinitionCommitment(), grant.population())
                || grant.mapping() == null || claim.mappingVersion() != grant.mapping().version()
                || !claim.mappingCommitment().equals(grant.mapping().commitment())
                || claim.mappingVersion() != pseudonyms.mappingVersion()
                || !claim.mappingCommitment().equals(pseudonyms.mappingCommitment())
                || claim.identityVersion() != stored.scope().identityVersion()
                || !pseudonyms.scope(source).equals(stored.scope())
                || !pseudonyms.sourceEvent(source.institution(), source.source(), claim.sourceEventId()).equals(stored.sourceEvent())
                || !claim.transactionCommitment().equals(stored.transaction())
                || !claim.occurrenceCommitment().equals(stored.occurrence())
                || !claim.merchantPseudonym().equals(stored.merchant())
                || claim.sequence() != stored.sequence() || !claim.eventTime().equals(stored.eventTime())
                || !claim.sourceObservedAt().equals(stored.sourceObservedAt())
                || !claim.enrichedCommitment().equals(stored.payloadCommitment())) {
            return false;
        }
        return true;
    }

    /** Rehydrates the latest durable coverage capability for restart-safe chain continuation. */
    VerifiedCoverage verifyStoredCoverage(MerchantAssertionAnchor anchor, SourceSignatureVerifier.Verified origin,
                                         CoverageSeal stored) {
        if (anchor == null || anchor.role() != Role.COVERAGE || !validOrigin(origin) || stored == null) { return null; }
        byte[] payload = boundedAssertion(anchor.assertionBytes());
        byte[] proof = boundedProof(anchor.proofBytes());
        if (payload == null || proof == null) { return null; }
        MerchantCoverageAssertion claim;
        try { claim = MerchantAssertionCodec.parseCoverage(payload); }
        catch (RuntimeException malformed) { return null; }
        SourceSignatureVerifier.Verified signature = signature(proof, payload, Role.COVERAGE);
        MerchantAssertionTrustPolicy.Grant grant = trustPolicy.grant(Role.COVERAGE, signature);
        SourceScope source = origin.scope();
        String commitment = signature == null ? null : assertionCommitment(Role.COVERAGE, signature, payload);
        if (grant == null || !Objects.equals(commitment, anchor.commitment())
                || !Objects.equals(stored.evidenceCommitment(), anchor.commitment())
                || !matchesSource(claim.sourceIssuer(), claim.institution(), claim.source(), claim.sourceEpoch(), claim.identityVersion(), grant.sourceIdentity())
                || !claim.sourceIssuer().equals(source.issuer()) || !claim.institution().equals(source.institution())
                || !claim.source().equals(source.source()) || !claim.sourceEpoch().equals(source.sourceEpoch())
                || !claim.merchantGrantId().equals(grant.merchantGrantId())
                || claim.merchantGrantVersion() != grant.merchantGrantVersion()
                || !claim.merchantPolicyVersion().equals(grant.key().policyVersion())
                || !claim.coverageId().equals(signature.scope().sourceEventId())
                || !claim.watermark().equals(signature.scope().eventTime())
                || claim.throughSequence() != stored.throughSequence()
                || !claim.coverageStart().equals(stored.coverageStart()) || !claim.watermark().equals(stored.watermark())
                || claim.sequenceOrigin() != 1 || claim.fromSequence() < 1
                || !matchesPopulation(claim.feedId(), claim.populationId(), claim.populationDefinitionVersion(),
                claim.populationDefinitionCommitment(), grant.population())
                || !claim.inclusionRulesCommitment().equals(grant.population().inclusionRulesCommitment())
                || !claim.exclusionRulesCommitment().equals(grant.population().exclusionRulesCommitment())
                || claim.coverageStart().isBefore(grant.population().earliestCoverageStart())
                || Duration.between(claim.coverageStart(), claim.watermark()).compareTo(MAX_COVERAGE_WINDOW) > 0
                || claim.watermark().isAfter(clock.instant().plus(FUTURE_SKEW))) { return null; }
        return new VerifiedCoverage(verifierIdentity, claim, signature, origin, grant, payload, proof, commitment);
    }

    /** Rechecks current source and Merchant key policy before a capability can be reused. */
    Denied currentDenial(Verified evidence) {
        if (evidence == null || evidence.verifierIdentity != verifierIdentity) { return Denied.POLICY_CHANGED; }
        if (!validOrigin(evidence.origin)) { return Denied.SOURCE_NOT_AUTHORIZED; }
        Role role = evidence instanceof VerifiedObservation ? Role.OBSERVATION
                : evidence instanceof VerifiedCoverage ? Role.COVERAGE : null;
        if (role == null || assertionVerifier.currentDenial(evidence.signature) != null
                || !trustPolicy.stillAuthorized(evidence.grant, role, evidence.signature)) {
            return Denied.POLICY_CHANGED;
        }
        return null;
    }

    private boolean validCoverageChain(MerchantCoverageAssertion claim, VerifiedCoverage previous) {
        if (claim.generation() == 1) {
            return previous == null && claim.fromSequence() == 1 && claim.previousCoverageCommitment() == null;
        }
        if (previous == null || currentDenial(previous) != null) { return false; }
        MerchantCoverageAssertion prior = previous.assertion();
        if (prior.throughSequence() == Long.MAX_VALUE || prior.generation() == Long.MAX_VALUE) { return false; }
        return claim.generation() == prior.generation() + 1
                && claim.fromSequence() == prior.throughSequence() + 1
                && claim.previousCoverageCommitment().equals(previous.assertionCommitment())
                && samePopulation(claim, prior) && claim.sourceIssuer().equals(prior.sourceIssuer())
                && claim.institution().equals(prior.institution()) && claim.source().equals(prior.source())
                && claim.sourceEpoch().equals(prior.sourceEpoch()) && claim.identityVersion() == prior.identityVersion()
                && !claim.coverageStart().isBefore(prior.coverageStart()) && !claim.watermark().isBefore(prior.watermark());
    }

    private boolean validOrigin(SourceSignatureVerifier.Verified origin) {
        return origin != null && originVerifier.currentDenial(origin) == null;
    }

    private SourceSignatureVerifier.Verified signature(byte[] proof, byte[] payload, Role role) {
        SourceSignatureVerifier.Result result = assertionVerifier.verify(proof, payload,
                new SourceSignatureVerifier.Context(role.purpose(), MerchantAssertionTrustPolicy.AUDIENCE));
        return result instanceof SourceSignatureVerifier.Verified verified ? verified : null;
    }

    private static boolean matchesSource(String issuer, String institution, String source, String epoch, int identityVersion,
                                        MerchantAssertionTrustPolicy.SourceIdentity expected) {
        return expected.issuer().equals(issuer) && expected.institution().equals(institution)
                && expected.source().equals(source) && expected.sourceEpoch().equals(epoch)
                && expected.identityVersion() == identityVersion;
    }

    private static boolean matchesPopulation(String feedId, String populationId, int definitionVersion,
                                             String definitionCommitment,
                                             MerchantAssertionTrustPolicy.Population expected) {
        return expected.feedId().equals(feedId) && expected.id().equals(populationId)
                && expected.definitionVersion() == definitionVersion
                && expected.definitionCommitment().equals(definitionCommitment);
    }

    private static boolean samePopulation(MerchantCoverageAssertion left, MerchantCoverageAssertion right) {
        return left.feedId().equals(right.feedId()) && left.populationId().equals(right.populationId())
                && left.populationDefinitionVersion() == right.populationDefinitionVersion()
                && left.populationDefinitionCommitment().equals(right.populationDefinitionCommitment())
                && left.inclusionRulesCommitment().equals(right.inclusionRulesCommitment())
                && left.exclusionRulesCommitment().equals(right.exclusionRulesCommitment());
    }

    private static String assertionCommitment(Role role, SourceSignatureVerifier.Verified signature, byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("merchant-assertion-commitment/v1\0" + role.name() + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(HexFormat.of().parseHex(signature.keyCommitment()));
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(payload.length).array());
            digest.update(payload);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception unavailable) {
            throw new IllegalStateException("MERCHANT_ASSERTION_COMMITMENT_UNAVAILABLE");
        }
    }

    private static byte[] boundedAssertion(byte[] value) {
        return value == null || value.length == 0 || value.length > MerchantAssertionCodec.MAX_ASSERTION_BYTES ? null : value.clone();
    }

    private static byte[] boundedProof(byte[] value) {
        return value == null || value.length == 0 || value.length > 4_096 ? null : value.clone();
    }

    enum Role {
        OBSERVATION(OBSERVATION_PURPOSE), COVERAGE(COVERAGE_PURPOSE);
        private final String purpose;
        Role(String purpose) { this.purpose = purpose; }
        String purpose() { return purpose; }
    }

    sealed interface Result permits Verified, Denied { }

    /** Opaque proof capability. Its constructors and source lineage stay private to this package. */
    abstract static sealed class Verified implements Result permits VerifiedObservation, VerifiedCoverage {
        private final Object verifierIdentity;
        private final SourceSignatureVerifier.Verified signature;
        private final SourceSignatureVerifier.Verified origin;
        private final MerchantAssertionTrustPolicy.Grant grant;
        private final byte[] originalAssertion;
        private final byte[] proof;
        private final String assertionCommitment;

        private Verified(Object verifierIdentity, SourceSignatureVerifier.Verified signature,
                         SourceSignatureVerifier.Verified origin, MerchantAssertionTrustPolicy.Grant grant,
                         byte[] originalAssertion, byte[] proof, String assertionCommitment) {
            this.verifierIdentity = verifierIdentity;
            this.signature = signature;
            this.origin = origin;
            this.grant = grant;
            this.originalAssertion = originalAssertion.clone();
            this.proof = proof.clone();
            this.assertionCommitment = assertionCommitment;
        }

        SourceSignatureVerifier.Verified origin() { return origin; }
        String signerKeyCommitment() { return signature.keyCommitment(); }
        String signerPolicyVersion() { return signature.policyVersion(); }
        Instant verifiedAt() { return signature.verifiedAt(); }
        String assertionCommitment() { return assertionCommitment; }
        byte[] originalAssertionBytes() { return originalAssertion.clone(); }
        byte[] proofBytes() { return proof.clone(); }
        @Override public final String toString() { return "VerifiedMerchantAssertion[redacted]"; }
    }

    static final class VerifiedObservation extends Verified {
        private final MerchantObservationAssertion assertion;
        private VerifiedObservation(Object verifierIdentity, MerchantObservationAssertion assertion,
                                   SourceSignatureVerifier.Verified signature, SourceSignatureVerifier.Verified origin,
                                   MerchantAssertionTrustPolicy.Grant grant, byte[] payload, byte[] proof,
                                   String assertionCommitment) {
            super(verifierIdentity, signature, origin, grant, payload, proof, assertionCommitment);
            this.assertion = assertion;
        }
        MerchantObservationAssertion assertion() { return assertion; }
    }

    static final class VerifiedCoverage extends Verified {
        private final MerchantCoverageAssertion assertion;
        private VerifiedCoverage(Object verifierIdentity, MerchantCoverageAssertion assertion,
                                 SourceSignatureVerifier.Verified signature, SourceSignatureVerifier.Verified origin,
                                 MerchantAssertionTrustPolicy.Grant grant, byte[] payload, byte[] proof,
                                 String assertionCommitment) {
            super(verifierIdentity, signature, origin, grant, payload, proof, assertionCommitment);
            this.assertion = assertion;
        }
        MerchantCoverageAssertion assertion() { return assertion; }
    }

    /** Caller fields are not trusted: P03 must construct this only from source-authenticated persisted evidence. */
    record ExpectedSourceBinding(SourceSignatureVerifier.Verified origin, String receiptId,
                                 String rawCommitment, String enrichedCommitment,
                                 String transactionCommitment, String occurrenceCommitment,
                                 String merchantPseudonym, int mappingVersion, String mappingCommitment) {
        ExpectedSourceBinding {
            Objects.requireNonNull(origin, "MERCHANT_ORIGIN_REQUIRED");
            MerchantAssertionCodec.commitment(receiptId);
            MerchantAssertionCodec.commitment(rawCommitment);
            MerchantAssertionCodec.commitment(enrichedCommitment);
            MerchantAssertionCodec.commitment(transactionCommitment);
            MerchantAssertionCodec.commitment(occurrenceCommitment);
            MerchantAssertionCodec.commitment(merchantPseudonym);
            MerchantAssertionCodec.commitment(mappingCommitment);
            if (mappingVersion < 1 || mappingVersion > 65_535) { throw new IllegalArgumentException("MERCHANT_BINDING_INVALID"); }
        }
        @Override public String toString() { return "ExpectedMerchantSourceBinding[redacted]"; }
    }

    enum Denied implements Result {
        PAYLOAD_REJECTED,
        PROOF_REJECTED,
        MALFORMED_ASSERTION,
        SIGNATURE_OR_POLICY_DENIED,
        AUTHORITY_NOT_AUTHORIZED,
        SOURCE_NOT_AUTHORIZED,
        SOURCE_BINDING_MISMATCH,
        MAPPING_NOT_AUTHORIZED,
        POPULATION_NOT_AUTHORIZED,
        TIME_NOT_AUTHORIZED,
        COVERAGE_CHAIN_MISMATCH,
        POLICY_CHANGED
    }
}
