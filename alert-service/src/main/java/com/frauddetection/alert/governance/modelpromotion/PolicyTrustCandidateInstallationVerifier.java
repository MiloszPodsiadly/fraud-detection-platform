package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProof;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProofVerifier;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent protected-host check of an approved bundle against the currently installed source file. */
public final class PolicyTrustCandidateInstallationVerifier {
    private final ObjectMapper mapper;
    private final Clock clock;

    public PolicyTrustCandidateInstallationVerifier(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public VerifiedCandidate verify(PolicyTrustChangeService.InstallationBundle bundle,
            Path currentRegistry, long protectedFloor) {
        if (bundle == null || bundle.request() == null || bundle.candidate() == null
                || !"PENDING_OPERATOR_ACTION".equals(bundle.request().status())) {
            throw new SecurityException("Approved exact trust installation request is required");
        }
        var request = bundle.request();
        var candidate = bundle.candidate();
        if (!request.id().equals(candidate.id())
                || request.intent().registry() != candidate.registry()
                || !request.intent().action().name().equals(candidate.action())
                || !request.intentSha256().equals(GovernanceCanonicalJson.sha256(mapper, request.intent()))
                || request.baseGeneration() != candidate.baseGeneration()
                || !request.baseRegistrySha256().equals(candidate.baseSha256())
                || request.intent().requestedGeneration() != candidate.generation()
                || !request.candidateSha256().equals(candidate.sha256())
                || request.affectsProduction() != candidate.affectsProduction()
                || !candidate.sha256().equals(GovernanceCanonicalJson.sha256(
                        candidate.canonicalJson().getBytes(StandardCharsets.UTF_8)))
                || request.requestedBy().equals(request.reviewedBy())) {
            throw new SecurityException("Trust candidate, operation or independent reviewer differs");
        }
        var snapshots = new PolicyTrustRegistrySnapshotService(mapper,
                candidate.registry() == PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS
                        ? currentRegistry.toString() : "",
                candidate.registry() == PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS
                        ? currentRegistry.toString() : "",
                candidate.registry() == PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS
                        ? currentRegistry.toString() : "",
                protectedFloor, protectedFloor, protectedFloor);
        var expected = new PolicyTrustCandidateBuilder(snapshots, mapper, clock).build(request.intent());
        String diffJson = new String(GovernanceCanonicalJson.serialize(mapper,
                mapper.valueToTree(expected.diff())), StandardCharsets.UTF_8);
        if (expected.baseGeneration() != candidate.baseGeneration()
                || !expected.baseSha256().equals(candidate.baseSha256())
                || !expected.sha256().equals(candidate.sha256())
                || !expected.canonicalJson().equals(candidate.canonicalJson())
                || !expected.summary().equals(candidate.reviewSummary())
                || expected.affectsProduction() != candidate.affectsProduction()
                || !diffJson.equals(candidate.semanticDiffJson())) {
            throw new SecurityException("Delivered candidate is not the single authorized source mutation");
        }
        verifyReceipt(bundle.requestProof(), bundle.requestAudit(), request, "REQUESTED",
                request.requestedBy(), request.requesterCredentialId(), request.requestAssertionSha256(),
                request.requestProofId(), request.requestedAt(), requestContract(request));
        verifyReceipt(bundle.reviewProof(), bundle.reviewAudit(), request, "APPROVE",
                request.reviewedBy(), request.reviewerCredentialId(), request.reviewAssertionSha256(),
                request.reviewProofId(), request.reviewedAt(), reviewContract(request));
        return new VerifiedCandidate(candidate.registry(), candidate.generation(), candidate.sha256(),
                candidate.canonicalJson());
    }

    public VerifiedCandidate stage(PolicyTrustChangeService.InstallationBundle bundle,
            Path currentRegistry, Path newRegistry, long protectedFloor) throws java.io.IOException {
        VerifiedCandidate verified = verify(bundle, currentRegistry, protectedFloor);
        Files.writeString(newRegistry, verified.canonicalJson(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (!verified.sha256().equals(GovernanceCanonicalJson.sha256(Files.readAllBytes(newRegistry)))) {
            throw new SecurityException("Staged trust registry bytes differ from the approved candidate");
        }
        return verified;
    }

    private void verifyReceipt(GovernanceBoundWebAuthnProof proof, PolicyTrustChangeAuditDocument audit,
            PolicyTrustChangeDocument request, String action, String actorId,
            String credentialId, String assertionSha256, String proofId,
            java.time.Instant acceptedAt, Map<String, Object> contract) {
        if (proof == null || audit == null || proofId == null || actorId == null
                || !proofId.equals(proof.id()) || !actorId.equals(proof.actorId())
                || !credentialId.equals(proof.credentialId())
                || !assertionSha256.equals(proof.assertionSha256())
                || !acceptedAt.equals(proof.acceptedAt())
                || !request.id().equals(audit.requestId()) || !action.equals(audit.action())
                || !actorId.equals(audit.actorId())
                || !request.intentSha256().equals(audit.intentSha256())
                || !assertionSha256.equals(audit.assertionSha256())
                || !proofId.equals(audit.proofId()) || !acceptedAt.equals(audit.occurredAt())) {
            throw new SecurityException("Trust approval receipt and signed proof differ");
        }
        new GovernanceBoundWebAuthnProofVerifier(mapper).verifyHistorical(proof, contract);
    }

    private Map<String, Object> requestContract(PolicyTrustChangeDocument request) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", "com.frauddetection.policy-trust.change-request.v1");
        contract.put("intentSha256", request.intentSha256());
        contract.put("registry", request.intent().registry().name());
        contract.put("baseGeneration", request.baseGeneration());
        contract.put("baseRegistrySha256", request.baseRegistrySha256());
        contract.put("requestedGeneration", request.intent().requestedGeneration());
        contract.put("candidateRegistrySha256", request.candidateSha256());
        return contract;
    }

    private Map<String, Object> reviewContract(PolicyTrustChangeDocument request) {
        return Map.of("domain", "com.frauddetection.policy-trust.change-review.v1",
                "requestId", request.id(), "intentSha256", request.intentSha256(),
                "baseGeneration", request.baseGeneration(),
                "baseRegistrySha256", request.baseRegistrySha256(),
                "candidateRegistrySha256", request.candidateSha256(),
                "requestedGeneration", request.intent().requestedGeneration(),
                "decision", "APPROVE");
    }

    public record VerifiedCandidate(PolicyTrustRegistrySnapshotService.Registry registry,
            long generation, String sha256, String canonicalJson) { }
}
