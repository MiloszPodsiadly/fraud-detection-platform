package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.frauddetection.common.events.source.SourceReceiptIdentity;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private proof verification only, with no Spring bean, write capability, or history-read permission.
 * Reuses the source verifier's bounded Ed25519 envelope over the original assertion bytes.
 * Source payload commitment, receiver RAW commitment, and ENRICHED commitment are distinct values.
 */
final class DeviceAssertionVerifier {
    static final String AUDIENCE = "feature-enricher-device-private";
    static final String TRANSACTION_PURPOSE = "TRANSACTION_SOURCE_ATTESTATION";
    private final SourceSignatureVerifier originVerifier;
    private final SourceSignatureVerifier assertionVerifier;
    private final Map<KeyId, Grant> grants;
    private final Map<KeyId, GrantCommitment> grantCommitments;
    private final Map<KeyId, String> grantInstitutionTokens = new ConcurrentHashMap<>();
    private final Clock clock;

    DeviceAssertionVerifier(SourceVerificationProperties originPolicy, List<Grant> grants, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "DEVICE_ASSERTION_CLOCK_REQUIRED");
        this.originVerifier = new SourceSignatureVerifier(Objects.requireNonNull(originPolicy), clock);
        List<Grant> copy = List.copyOf(grants);
        if (copy.size() > 64) { throw invalidPolicy(); }
        Map<KeyId, Grant> configured = new HashMap<>();
        Map<String, Role> keyRoles = new HashMap<>();
        for (Grant grant : copy) {
            if (configured.putIfAbsent(KeyId.from(grant.key()), grant) != null
                    || originPolicy.keys().stream().anyMatch(key -> key.publicKeyBase64().equals(grant.key().publicKeyBase64()))) {
                throw invalidPolicy();
            }
            Role previous = keyRoles.putIfAbsent(grant.key().publicKeyBase64(), grant.role());
            if (previous != null && previous != grant.role()) { throw invalidPolicy(); }
        }
        this.grants = Map.copyOf(configured);
        Map<KeyId, GrantCommitment> commitments = new HashMap<>();
        configured.forEach((key, grant) -> commitments.put(key,
                new GrantCommitment(authorizationCommitment(grant), populationCommitment(grant.population()),
                        DevicePseudonyms.sourceCode(grant.sourceScope().source()))));
        this.grantCommitments = Map.copyOf(commitments);
        this.assertionVerifier = new SourceSignatureVerifier(
                new SourceVerificationProperties(copy.stream().map(Grant::key).toList()), clock);
    }

    Result verifyObservation(byte[] proof, byte[] originalAssertion, ExpectedBinding expected) {
        if (expected == null || originDenied(expected.origin())) { return Denied.SOURCE_NOT_AUTHORIZED; }
        byte[] payload = boundedCopy(originalAssertion);
        if (payload == null) { return Denied.PAYLOAD_REJECTED; }
        SourceSignatureVerifier.Verified signature = signature(proof, payload, Role.OBSERVATION);
        if (signature == null) { return Denied.SIGNATURE_OR_POLICY_DENIED; }
        Grant grant = grants.get(KeyId.from(signature.scope()));
        if (!authorized(grant, Role.OBSERVATION, signature, expected.origin())) { return Denied.AUTHORITY_NOT_AUTHORIZED; }
        DeviceAssertions.Observation claim;
        try { claim = DeviceAssertions.observation(payload); }
        catch (RuntimeException malformed) { return Denied.MALFORMED_ASSERTION; }
        SourceScope origin = expected.origin().scope();
        if (!claim.scope().equals(grant.sourceScope()) || !claim.sourceEventId().equals(origin.sourceEventId())
                || !claim.sourceEventId().equals(signature.scope().sourceEventId())
                || !claim.eventTime().equals(origin.eventTime()) || !claim.eventTime().equals(signature.scope().eventTime())
                || !claim.receiptId().equals(SourceReceiptIdentity.identity(origin))
                || !claim.receiptId().equals(expected.receiptId())
                || !claim.rawCommitment().equals(expected.rawCommitment())
                || !claim.enrichedCommitment().equals(expected.enrichedCommitment())
                || !claim.transactionKey().equals(expected.transactionKey())
                || !claim.customerToken().equals(expected.customerToken()) || !claim.deviceToken().equals(expected.deviceToken())) {
            return Denied.BINDING_MISMATCH;
        }
        if (!claim.bindingAuthority().equals(grant.bindingAuthority()) || !claim.providerBasis().equals(grant.providerBasis())
                || !claim.policyVersion().equals(grant.key().policyVersion())) { return Denied.AUTHORITY_NOT_AUTHORIZED; }
        if (claim.pseudonymKeyVersion() != grant.pseudonymKeyVersion()
                || claim.pseudonymKeyVersion() != expected.pseudonymKeyVersion()) { return Denied.PSEUDONYM_KEY_NOT_AUTHORIZED; }
        if (!population(grant.population(), claim.populationId(), claim.populationDefinitionVersion(),
                claim.populationDefinitionCommitment()) || claim.sourceSequence() < grant.population().sequenceOrigin()) {
            return Denied.POPULATION_NOT_AUTHORIZED;
        }
        if (claim.observedAt().isBefore(claim.eventTime().minusSeconds(300))
                || claim.observedAt().isAfter(claim.eventTime().plusSeconds(120))
                || claim.observedAt().isAfter(clock.instant().plusSeconds(120))
                || claim.eventTime().isAfter(clock.instant().plusSeconds(120))) { return Denied.TIME_NOT_AUTHORIZED; }
        return new VerifiedObservation(claim, signature, expected.origin(), grant);
    }

    Result verifyCoverage(byte[] proof, byte[] originalAssertion, SourceSignatureVerifier.Verified origin) {
        if (originDenied(origin)) { return Denied.SOURCE_NOT_AUTHORIZED; }
        byte[] payload = boundedCopy(originalAssertion);
        if (payload == null) { return Denied.PAYLOAD_REJECTED; }
        SourceSignatureVerifier.Verified signature = signature(proof, payload, Role.COVERAGE);
        if (signature == null) { return Denied.SIGNATURE_OR_POLICY_DENIED; }
        Grant grant = grants.get(KeyId.from(signature.scope()));
        if (!authorized(grant, Role.COVERAGE, signature, origin)) { return Denied.AUTHORITY_NOT_AUTHORIZED; }
        DeviceAssertions.Coverage claim;
        try { claim = DeviceAssertions.coverage(payload); }
        catch (RuntimeException malformed) { return Denied.MALFORMED_ASSERTION; }
        if (!claim.scope().equals(grant.sourceScope())
                || !claim.coverageId().equals(signature.scope().sourceEventId())
                || !claim.watermark().equals(signature.scope().eventTime())
                || !claim.policyVersion().equals(grant.key().policyVersion())) { return Denied.BINDING_MISMATCH; }
        Population population = grant.population();
        if (!population(population, claim.populationId(), claim.populationDefinitionVersion(), claim.populationDefinitionCommitment())
                || !population.inclusionRule().equals(claim.inclusionRule())
                || !population.exclusionRule().equals(claim.exclusionRule())
                || population.sequenceOrigin() != claim.sequenceOrigin()
                || claim.coverageStart().isBefore(population.earliestCoverageStart())) { return Denied.POPULATION_NOT_AUTHORIZED; }
        if (claim.pseudonymKeyVersion() != grant.pseudonymKeyVersion()) { return Denied.PSEUDONYM_KEY_NOT_AUTHORIZED; }
        if (claim.watermark().isAfter(clock.instant().plusSeconds(120))) { return Denied.TIME_NOT_AUTHORIZED; }
        // This authenticates the statement, not upstream truth, ledger continuity, or a complete 30-day window.
        return new VerifiedCoverage(claim, signature, origin, grant);
    }

    /** Recheck current keys and the complete local authorization grant before any later use. */
    Denied currentDenial(Verified evidence) {
        if (evidence == null || originDenied(evidence.origin)) { return Denied.SOURCE_NOT_AUTHORIZED; }
        Grant current = grants.get(KeyId.from(evidence.signature.scope()));
        if (!Objects.equals(current, evidence.grant) || assertionVerifier.currentDenial(evidence.signature) != null) {
            return Denied.POLICY_CHANGED;
        }
        return null;
    }

    static String populationCommitment(Population p) {
        return DeviceEpoch.digest("device-population-policy/v1", p.id(), p.definitionVersion(), p.definitionCommitment(), p.inclusionRule(),
                p.exclusionRule(), Long.toString(p.sequenceOrigin()), p.earliestCoverageStart().toString());
    }
    String authorizationCommitment(Verified evidence) { return authorizationCommitment(evidence.grant); }
    private static String authorizationCommitment(Grant g) {
        var k = g.key(); var s = g.sourceScope();
        return DeviceEpoch.digest("device-assertion-authorization/v1", g.role().name(), k.issuer(), k.institution(), k.source(), k.sourceEpoch(),
                k.signingKeyVersion(), k.publicKeyBase64(), k.notBefore().toString(), k.notAfter().toString(), Boolean.toString(k.revoked()),
                k.purpose(), k.audience(), k.policyVersion(), s.sourceIssuer(), s.institution(), s.source(), s.sourceEpoch(),
                populationCommitment(g.population()), Integer.toString(g.pseudonymKeyVersion()), g.bindingAuthority(), g.providerBasis());
    }

    /** Policy check on a previously authenticated durable record; never authenticates an arbitrary caller record. */
    boolean currentStoredObservation(DeviceObservationStore.Provenance p, com.frauddetection.common.events.source.SourceLineage lineage, int pseudonymVersion) {
        if (lineage == null || lineage.state() != com.frauddetection.common.events.source.SourceLineage.State.VERIFIED
                || !p.receiptId().equals(lineage.receiptId()) || !p.enrichedCommitment().equals(lineage.eventCommitment())) { return false; }
        var origin = lineage.source();
        KeyId key = new KeyId(p.deviceIssuer(), origin.sourceEpoch(), p.deviceKeyVersion());
        Grant grant = grants.get(key);
        GrantCommitment commitment = grantCommitments.get(key);
        if (grant == null || grant.role() != Role.OBSERVATION || grant.pseudonymKeyVersion() != pseudonymVersion
                || !grant.sourceScope().matches(origin.issuer(), origin.institution(), origin.source(), origin.sourceEpoch())
                || commitment == null || !commitment.authorization().equals(p.authorizationCommitment())
                || !commitment.population().equals(p.populationCommitment()) || !origin.eventTime().equals(p.proofTime())) { return false; }
        return assertionVerifier.receiptPolicyDenialForPersistedScope(p.deviceIssuer(), origin.institution(), origin.source(),
                origin.sourceEpoch(), p.deviceKeyVersion(), origin.eventTime(), Role.OBSERVATION.purpose, AUDIENCE,
                p.deviceKeyCommitment(), p.devicePolicyVersion(), p.proofTime()) == null;
    }

    boolean currentStoredCoverage(DeviceObservationStore.CoverageAuthority p, DeviceEpoch epoch, String institution, String source,
                                  String populationCommitment, int pseudonymVersion, Instant coverageStart, DevicePseudonyms pseudonyms) {
        KeyId key = new KeyId(p.issuer(), epoch.sourceEpoch(), p.keyVersion());
        Grant grant = grants.get(key);
        GrantCommitment commitment = grantCommitments.get(key);
        if (grant == null || grant.role() != Role.COVERAGE || !epoch.sourceIssuer().equals(grant.sourceScope().sourceIssuer())
                || grant.pseudonymKeyVersion() != pseudonymVersion || pseudonyms.version() != pseudonymVersion
                || commitment == null || !commitment.authorization().equals(p.authorizationCommitment())
                || !commitment.population().equals(populationCommitment)
                || coverageStart.isBefore(grant.population().earliestCoverageStart())) { return false; }
        String grantInstitutionToken = grantInstitutionTokens.computeIfAbsent(key,
                ignored -> pseudonyms.institution(grant.sourceScope().institution()));
        if (!grantInstitutionToken.equals(institution) || !commitment.sourceCode().equals(source)) { return false; }
        return assertionVerifier.receiptPolicyDenialForPersistedScope(p.issuer(), grant.sourceScope().institution(),
                grant.sourceScope().source(), epoch.sourceEpoch(), p.keyVersion(), p.proofTime(), Role.COVERAGE.purpose, AUDIENCE,
                p.keyCommitment(), p.policyVersion(), p.proofTime()) == null;
    }

    private SourceSignatureVerifier.Verified signature(byte[] proof, byte[] payload, Role role) {
        var result = assertionVerifier.verify(proof, payload, new SourceSignatureVerifier.Context(role.purpose, AUDIENCE));
        return result instanceof SourceSignatureVerifier.Verified verified ? verified : null;
    }

    private boolean originDenied(SourceSignatureVerifier.Verified origin) {
        return origin == null || !TRANSACTION_PURPOSE.equals(origin.scope().purpose()) || originVerifier.currentDenial(origin) != null;
    }

    private static boolean authorized(Grant grant, Role role, SourceSignatureVerifier.Verified proof,
                                      SourceSignatureVerifier.Verified origin) {
        SourceScope source = origin.scope();
        return grant != null && grant.role() == role
                && !proof.keyCommitment().equals(origin.keyCommitment())
                && grant.sourceScope().matches(source.issuer(), source.institution(), source.source(), source.sourceEpoch());
    }

    private static boolean population(Population allowed, String id, String version, String commitment) {
        return allowed.id().equals(id) && allowed.definitionVersion().equals(version) && allowed.definitionCommitment().equals(commitment);
    }

    private static byte[] boundedCopy(byte[] value) {
        return value == null || value.length == 0 || value.length > DeviceAssertions.MAX_PAYLOAD_BYTES ? null : value.clone();
    }

    private static IllegalArgumentException invalidPolicy() { return new IllegalArgumentException("DEVICE_ASSERTION_POLICY_INVALID"); }

    enum Role {
        OBSERVATION("DEVICE_OBSERVATION_ASSERTION"), COVERAGE("DEVICE_COVERAGE_ASSERTION");
        final String purpose;
        Role(String purpose) { this.purpose = purpose; }
    }

    /** An explicit server-owned authorization for a contractual population; not inferred from a seal. */
    @JsonIgnoreType
    record Population(String id, String definitionVersion, String definitionCommitment, String inclusionRule,
                      String exclusionRule, long sequenceOrigin, Instant earliestCoverageStart) {
        Population {
            DeviceAssertions.reference(id); DeviceAssertions.reference(definitionVersion);
            DeviceAssertions.token(definitionCommitment); DeviceAssertions.reference(inclusionRule);
            DeviceAssertions.reference(exclusionRule);
            if (sequenceOrigin < 1 || earliestCoverageStart == null || earliestCoverageStart.getNano() % 1_000_000 != 0) { throw invalidPolicy(); }
        }
        @Override public String toString() { return "DevicePopulationPolicy[redacted]"; }
    }

    @JsonIgnoreType
    record Grant(Role role, SourceVerificationProperties.TrustedKey key, DeviceAssertions.Scope sourceScope,
                 Population population, int pseudonymKeyVersion, String bindingAuthority, String providerBasis) {
        Grant {
            Objects.requireNonNull(role); Objects.requireNonNull(key); Objects.requireNonNull(sourceScope); Objects.requireNonNull(population);
            DeviceAssertions.reference(bindingAuthority); DeviceAssertions.reference(providerBasis);
            if (!role.purpose.equals(key.purpose()) || !AUDIENCE.equals(key.audience())
                    || sourceScope.sourceIssuer().equals(key.issuer())
                    || !sourceScope.institution().equals(key.institution()) || !sourceScope.source().equals(key.source())
                    || !sourceScope.sourceEpoch().equals(key.sourceEpoch()) || pseudonymKeyVersion < 1 || pseudonymKeyVersion > 65_535) {
                throw invalidPolicy();
            }
        }
        @Override public String toString() { return "DeviceAssertionGrant[redacted]"; }
    }

    /**
     * Expected private binding supplied by trusted code. This is not a durable receipt or admission capability.
     * P03 must derive these values from actual source RAW/ENRICHED lineage and the approved pseudonym mapper.
     */
    @JsonIgnoreType
    record ExpectedBinding(SourceSignatureVerifier.Verified origin, String receiptId, String rawCommitment,
                           String enrichedCommitment, String transactionKey, String customerToken, String deviceToken,
                           int pseudonymKeyVersion) {
        ExpectedBinding {
            Objects.requireNonNull(origin); DeviceAssertions.token(receiptId); DeviceAssertions.token(rawCommitment);
            DeviceAssertions.token(enrichedCommitment); DeviceAssertions.token(transactionKey);
            DeviceAssertions.token(customerToken); DeviceAssertions.token(deviceToken);
            if (pseudonymKeyVersion < 1 || pseudonymKeyVersion > 65_535) { throw new IllegalArgumentException("DEVICE_BINDING_INVALID"); }
        }
        @Override public String toString() { return "ExpectedDeviceBinding[redacted]"; }
    }

    sealed interface Result permits Verified, Denied { }

    @JsonIgnoreType
    abstract static sealed class Verified implements Result permits VerifiedObservation, VerifiedCoverage {
        private final SourceSignatureVerifier.Verified signature;
        private final SourceSignatureVerifier.Verified origin;
        private final Grant grant;
        private Verified(SourceSignatureVerifier.Verified signature, SourceSignatureVerifier.Verified origin, Grant grant) {
            this.signature = signature; this.origin = origin; this.grant = grant;
        }
        String payloadCommitment() { return signature.scope().payloadCommitment(); }
        String signingKeyVersion() { return signature.scope().signingKeyVersion(); }
        String issuer() { return signature.scope().issuer(); }
        String keyCommitment() { return signature.keyCommitment(); }
        Population population() { return grant.population(); }
        String policyVersion() { return signature.policyVersion(); }
        Instant verifiedAt() { return signature.verifiedAt(); }
        @Override public final String toString() { return "VerifiedDeviceAssertion[redacted]"; }
    }

    @JsonIgnoreType
    static final class VerifiedObservation extends Verified {
        private final DeviceAssertions.Observation claims;
        private VerifiedObservation(DeviceAssertions.Observation claims, SourceSignatureVerifier.Verified signature,
                                    SourceSignatureVerifier.Verified origin, Grant grant) {
            super(signature, origin, grant); this.claims = claims;
        }
        DeviceAssertions.Observation claims() { return claims; }
    }

    @JsonIgnoreType
    static final class VerifiedCoverage extends Verified {
        private final DeviceAssertions.Coverage claims;
        private VerifiedCoverage(DeviceAssertions.Coverage claims, SourceSignatureVerifier.Verified signature,
                                 SourceSignatureVerifier.Verified origin, Grant grant) {
            super(signature, origin, grant); this.claims = claims;
        }
        DeviceAssertions.Coverage claims() { return claims; }
    }

    enum Denied implements Result {
        SOURCE_NOT_AUTHORIZED, PAYLOAD_REJECTED, SIGNATURE_OR_POLICY_DENIED, MALFORMED_ASSERTION,
        AUTHORITY_NOT_AUTHORIZED, BINDING_MISMATCH, POPULATION_NOT_AUTHORIZED,
        PSEUDONYM_KEY_NOT_AUTHORIZED, TIME_NOT_AUTHORIZED, POLICY_CHANGED
    }

    private record KeyId(String issuer, String epoch, String version) {
        static KeyId from(SourceVerificationProperties.TrustedKey key) { return new KeyId(key.issuer(), key.sourceEpoch(), key.signingKeyVersion()); }
        static KeyId from(SourceScope scope) { return new KeyId(scope.issuer(), scope.sourceEpoch(), scope.signingKeyVersion()); }
    }

    private record GrantCommitment(String authorization, String population, String sourceCode) { }
}
