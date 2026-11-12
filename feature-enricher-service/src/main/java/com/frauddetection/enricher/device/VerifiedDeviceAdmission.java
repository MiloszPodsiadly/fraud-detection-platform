package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.source.*;
import com.mongodb.client.MongoClient;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Private Stage A adapter. No runtime bean, public read, source publication or scoring activation. */
final class VerifiedDeviceAdmission {
    private final MongoSourceLineage source;
    private final SourceSignatureVerifier originVerifier;
    private final DeviceAssertionVerifier verifier;
    private final DevicePseudonyms pseudonyms;
    private final DeviceEpoch epoch;
    private final MongoDeviceObservationStore store;
    private final List<String> originAudiences;

    VerifiedDeviceAdmission(MongoClient client, String database, Clock clock, DeviceEpoch epoch,
                            MongoSourceLineage source, SourceVerificationProperties origins,
                            List<DeviceAssertionVerifier.Grant> grants, DevicePseudonyms pseudonyms) {
        this.source = Objects.requireNonNull(source); this.epoch = Objects.requireNonNull(epoch);
        this.pseudonyms = Objects.requireNonNull(pseudonyms);
        this.originVerifier = new SourceSignatureVerifier(origins, clock);
        this.originAudiences = origins.keys().stream().filter(k -> DeviceAssertionVerifier.TRANSACTION_PURPOSE.equals(k.purpose()))
                .map(SourceVerificationProperties.TrustedKey::audience).distinct().toList();
        this.verifier = new DeviceAssertionVerifier(origins, grants, clock);
        this.store = new MongoDeviceObservationStore(client, database, clock, epoch, new DeviceObservationStore.HistoryAuthority() {
            @Override public boolean current(DeviceObservationStore.Provenance p, int version) {
                SourceLineage lineage = source.enrichedBinding(p.receiptId(), p.rawCommitment(), p.enrichedCommitment());
                return lineage.state() == SourceLineage.State.VERIFIED && epoch.equals(new DeviceEpoch(lineage.source().issuer(), lineage.source().sourceEpoch()))
                        && verifier.currentStoredObservation(p, lineage, version);
            }
            @Override public boolean coverageCurrent(DeviceObservationStore.CoverageAuthority proof, String institution, String sourceCode,
                                                     String populationCommitment, int version, java.time.Instant coverageStart) {
                return verifier.currentStoredCoverage(proof, epoch, institution, sourceCode, populationCommitment, version, coverageStart, pseudonyms);
            }
        }, pseudonyms);
    }

    Ticket stage(byte[] sourceProof, byte[] originalSourcePayload, byte[] deviceProof, byte[] originalAssertion,
                 TransactionRawEvent raw, TransactionEnrichedEvent enriched) {
        SourceSignatureVerifier.Verified origin = verifiedOrigin(sourceProof, originalSourcePayload);
        SourceLineage rawLineage = source.raw(raw), enrichedLineage = source.enriched(enriched);
        requireLineage(origin.scope(), rawLineage, enrichedLineage, raw, enriched);
        String institution = origin.scope().institution();
        if (raw.deviceInfo() == null || !Objects.equals(raw.customerId(), enriched.customerId()) || !Objects.equals(raw.transactionId(), enriched.transactionId())) { throw denied(); }
        var expected = new DeviceAssertionVerifier.ExpectedBinding(origin, rawLineage.receiptId(), rawLineage.eventCommitment(), enrichedLineage.eventCommitment(),
                pseudonyms.transaction(institution, raw.customerId(), raw.transactionId()), pseudonyms.customer(institution, raw.customerId()),
                pseudonyms.device(institution, raw.deviceInfo().deviceId()), pseudonyms.version());
        var result = verifier.verifyObservation(deviceProof, originalAssertion, expected);
        if (!(result instanceof DeviceAssertionVerifier.VerifiedObservation observation)) { throw denied(); }
        var claim = observation.claims();
        var provenance = new DeviceObservationStore.Provenance(claim.receiptId(), claim.rawCommitment(), claim.enrichedCommitment(), observation.payloadCommitment(),
                DeviceAssertionVerifier.populationCommitment(observation.population()), origin.policyVersion(), observation.issuer(), observation.signingKeyVersion(),
                observation.policyVersion(), observation.keyCommitment(), verifier.authorizationCommitment(observation), claim.eventTime(), observation.population().sequenceOrigin());
        var input = new DeviceObservationStore.Observation(pseudonyms.institution(institution), DevicePseudonyms.sourceCode(origin.scope().source()),
                pseudonyms.event(institution, claim.sourceEventId()), claim.transactionKey(), claim.customerToken(), claim.deviceToken(), claim.pseudonymKeyVersion(),
                claim.sourceSequence(), claim.eventTime(), claim.observedAt(), observation.payloadCommitment(), DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION, provenance);
        var status = store.stage(input);
        return new Ticket(this, raw, enriched, origin, observation, input, status);
    }

    private SourceSignatureVerifier.Verified verifiedOrigin(byte[] proof, byte[] payload) {
        // Try only explicitly configured transaction audiences; never take an audience from the caller's envelope.
        for (String audience : originAudiences) {
            if (originVerifier.verify(proof, payload, new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, audience))
                    instanceof SourceSignatureVerifier.Verified verified) { return verified; }
        }
        throw denied();
    }

    void seal(Ticket ticket, byte[] proof, byte[] originalAssertion) {
        requireCurrent(ticket);
        var result = verifier.verifyCoverage(proof, originalAssertion, ticket.origin);
        if (!(result instanceof DeviceAssertionVerifier.VerifiedCoverage coverage)) { throw denied(); }
        var c = coverage.claims();
        if (c.pseudonymKeyVersion() != ticket.input.keyVersion()
                || !DeviceAssertionVerifier.populationCommitment(coverage.population()).equals(ticket.input.provenance().populationCommitment())) { throw denied(); }
        var seal = new DeviceObservationStore.CoverageSeal(ticket.input.institutionToken(), ticket.input.sourceCode(), c.throughSequence(),
                c.coverageStart(), c.watermark(), c.pseudonymKeyVersion(), new DeviceObservationStore.CoverageProof(c.sequenceOrigin(), c.fromSequence(), c.coverageGeneration(),
                c.previousCoverageCommitment(), c.coverageCommitment(), DeviceEpoch.digest("device-coverage-proof/v1", coverage.issuer(), coverage.signingKeyVersion(),
                coverage.policyVersion(), coverage.keyCommitment(), coverage.payloadCommitment()), DeviceAssertionVerifier.populationCommitment(coverage.population()),
                new DeviceObservationStore.CoverageAuthority(coverage.issuer(), coverage.signingKeyVersion(), coverage.policyVersion(), coverage.keyCommitment(),
                        verifier.authorizationCommitment(coverage), coverage.payloadCommitment(), c.coverageId(), c.watermark())));
        store.seal(seal);
    }

    DeviceObservationStore.CommitResult commit(Ticket ticket) {
        requireCurrent(ticket);
        var result = store.commit(ticket.input.institutionToken(), ticket.input.sourceCode(), ticket.input.sourceEventKey());
        requireCurrent(ticket);
        return result;
    }

    /** Stage B writes only the exact reference after source publication; never creates a Stage A snapshot. */
    void bindScored(Ticket ticket, SourceOccurrenceAccess occurrences, SourceOccurrenceAccess.Reference reference) {
        requireCurrent(ticket);
        SourceLineage authorization = source.enriched(ticket.enriched);
        SourceScoredOccurrence scored = occurrences == null || reference == null ? null : occurrences.find(authorization, reference).orElse(null);
        if (scored == null || !MongoSourceOccurrenceStore.INITIAL_RUN.equals(scored.runId())
                || !ticket.origin.scope().equals(scored.source()) || !ticket.input.provenance().receiptId().equals(scored.receiptId())
                || !ticket.input.provenance().enrichedCommitment().equals(scored.enrichedCommitment())) { throw denied(); }
        store.bindScored(ticket.input, scored);
        requireCurrent(ticket);
        if (occurrences.find(source.enriched(ticket.enriched), reference).isEmpty()) { throw denied(); }
    }

    private void requireCurrent(Ticket ticket) {
        if (ticket == null || ticket.owner != this || verifier.currentDenial(ticket.observation) != null) { throw denied(); }
        requireLineage(ticket.origin.scope(), source.raw(ticket.raw), source.enriched(ticket.enriched), ticket.raw, ticket.enriched);
    }
    private void requireLineage(SourceScope scope, SourceLineage rawLineage, SourceLineage enrichedLineage,
                                TransactionRawEvent raw, TransactionEnrichedEvent enriched) {
        if (rawLineage.state() != SourceLineage.State.VERIFIED || enrichedLineage.state() != SourceLineage.State.VERIFIED
                || !epoch.equals(new DeviceEpoch(scope.issuer(), scope.sourceEpoch())) || !scope.equals(rawLineage.source()) || !scope.equals(enrichedLineage.source())
                || !rawLineage.receiptId().equals(enrichedLineage.receiptId()) || !SourceReceiptIdentity.identity(scope).equals(rawLineage.receiptId())
                || !SourceEventCommitment.raw(raw).equals(rawLineage.eventCommitment()) || !SourceEventCommitment.enriched(enriched).equals(enrichedLineage.eventCommitment())) { throw denied(); }
    }
    private static SecurityException denied() { return new SecurityException("DEVICE_VERIFIED_ADMISSION_DENIED"); }

    @JsonIgnoreType
    static final class Ticket {
        private final VerifiedDeviceAdmission owner;
        private final TransactionRawEvent raw;
        private final TransactionEnrichedEvent enriched;
        private final SourceSignatureVerifier.Verified origin;
        private final DeviceAssertionVerifier.VerifiedObservation observation;
        private final DeviceObservationStore.Observation input;
        private final DeviceObservationStore.StageResult status;
        private Ticket(VerifiedDeviceAdmission owner, TransactionRawEvent raw, TransactionEnrichedEvent enriched, SourceSignatureVerifier.Verified origin,
                       DeviceAssertionVerifier.VerifiedObservation observation, DeviceObservationStore.Observation input, DeviceObservationStore.StageResult status) {
            this.owner = owner; this.raw = raw; this.enriched = enriched; this.origin = origin; this.observation = observation; this.input = input; this.status = status;
        }
        DeviceObservationStore.StageResult status() { return status; }
        @Override public String toString() { return "DeviceAdmissionTicket[redacted]"; }
    }
}
