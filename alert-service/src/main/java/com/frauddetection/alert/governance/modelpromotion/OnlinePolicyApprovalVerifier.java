package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceHistoryDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceState;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnChallenge;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnCredential;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnEvidence;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnActionContract;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Shared verification of real WebAuthn assertions and current deployment-owned mandates. */
final class OnlinePolicyApprovalVerifier {
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String rpId;
    private final String origin;
    private final OnlinePolicyActorTrustRegistry actors;
    private final InternalPolicyApprovalTrustRegistry approvalTrust;

    OnlinePolicyApprovalVerifier(MongoTemplate mongo, ObjectMapper mapper, Clock clock, String rpId,
            String origin, OnlinePolicyActorTrustRegistry actors,
            InternalPolicyApprovalTrustRegistry approvalTrust) {
        this.mongo = Objects.requireNonNull(mongo);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.rpId = Objects.requireNonNull(rpId);
        this.origin = Objects.requireNonNull(origin);
        this.actors = Objects.requireNonNull(actors);
        this.approvalTrust = Objects.requireNonNull(approvalTrust);
    }

    OnlinePolicyActorTrustRegistry loadedActorTrust() { return actors; }

    Verified verify(PolicyPublicationJobDocument job) {
        return verify(job, null, true);
    }

    private Verified verify(PolicyPublicationJobDocument job, OnlinePolicyApprovalArtifact retained,
            boolean newPublication) {
        try {
            Instant now = clock.instant();
            if (job == null || job.draft() == null || job.decidedAt() == null
                    || job.submittedAt() == null || job.approvalEvidenceReference() == null
                    || job.submissionEvidenceReference() == null
                    || !job.draftSha256().equals(job.draft().canonicalSha256(mapper))
                    || !job.authorActorId().equals(job.draft().authorIdentity())
                    || !job.approverActorId().equals(job.draft().approverIdentity())
                    || job.authorActorId().equals(job.approverActorId())
                    || job.submittedAt().isAfter(job.decidedAt())
                    || job.decidedAt().isAfter(now.plusSeconds(30))) {
                throw new SecurityException("Publication job does not bind an independent exact draft approval");
            }
            OnlinePolicyApprovalArtifact.ActionProof submitted = verifyAction(job,
                    job.submissionEvidenceReference(), "POLICY_SUBMIT", "SUBMITTED", job.authorActorId(),
                    job.submittedAt(), job.proposalVersion() - 3,
                    retained == null ? null : retained.submission(), newPublication);
            OnlinePolicyApprovalArtifact.ActionProof approved = verifyAction(job,
                    job.approvalEvidenceReference(), "POLICY_APPROVE", "APPROVED", job.approverActorId(),
                    job.decidedAt(), job.proposalVersion() - 2,
                    retained == null ? null : retained.approval(), newPublication);
            if (submitted.evidence().credentialId().equals(approved.evidence().credentialId())
                    || submitted.publicKeyCose().equals(approved.publicKeyCose())) {
                throw new SecurityException("Author and approver cannot share an authenticator credential");
            }
            actors.require(submitted.evidence().actorId(), submitted.evidence().issuer(),
                    submitted.evidence().subject(), OnlinePolicyActorTrustRegistry.Role.POLICY_AUTHOR,
                    job.draft().governanceScope(), job.draft().intendedUse(), job.submittedAt(), now);
            actors.require(approved.evidence().actorId(), approved.evidence().issuer(),
                    approved.evidence().subject(), OnlinePolicyActorTrustRegistry.Role.POLICY_APPROVER,
                    job.draft().governanceScope(), job.draft().intendedUse(), job.decidedAt(), now);
            InternalPolicyApprovalTrustRegistry.TrustedKey publisher = mandate(job.draft().publisherIdentity(),
                    job.draft().publisherKeyId(), InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER,
                    job.draft(), now);
            InternalPolicyApprovalTrustRegistry.TrustedKey issuer = mandate(job.draft().issuerIdentity(),
                    job.draft().issuerKeyId(), InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER,
                    job.draft(), now);
            if (publisher.publicKeySha256().equals(issuer.publicKeySha256())) {
                throw new SecurityException("Publisher and issuer keys must be independent");
            }
            var content = job.draft().approvedContent(job.decidedAt());
            String digest = GovernanceCanonicalJson.sha256(mapper, content);
            var authorization = new InstitutionalPolicyPublicationAuthorization.Authorization(
                    content.policyId(), content.revision(), digest, content.governanceScope(),
                    content.intendedUse(), Instant.parse(content.effectiveFromInclusive()),
                    Instant.parse(content.effectiveToExclusive()), job.authorActorId(), job.approverActorId(),
                    content.provenance().approvalReference(), publisher.identity(), issuer.identity(), issuer.keyId(),
                    Math.max(actors.generation(), approvalTrust.generation()),
                    GovernanceCanonicalJson.sha256(mapper, Map.of("actorMandates", actors.canonicalSha256(),
                            "publisherMandates", approvalTrust.canonicalSha256())),
                    GovernanceCanonicalJson.sha256(Base64.getUrlDecoder().decode(approved.publicKeyCose())),
                    publisher.publicKeySha256(), issuer.publicKeySha256());
            return new Verified(new OnlinePolicyApprovalArtifact(job.id(), job.draft(), job.draftSha256(),
                    submitted, approved), authorization);
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Online policy approval proof is invalid", failure);
        }
    }

    Verified verifyPublished(ModelPromotionPolicy policy, InternalPolicyApprovalEvidenceDocument evidence) {
        if (policy == null || evidence == null || evidence.onlineApprovalProof() == null
                || !OnlinePolicyApprovalArtifact.VERSION.equals(evidence.proofKind())) {
            throw new SecurityException("Online approval evidence is unavailable");
        }
        OnlinePolicyApprovalArtifact storedProof = evidence.onlineApprovalProof();
        PolicyPublicationJobDocument job = mongo.findById(storedProof.publicationRequestId(),
                PolicyPublicationJobDocument.class);
        if (job == null || job.state() != PolicyPublicationJobDocument.State.PUBLISHED
                || !policy.canonicalSha256().equals(job.publishedPolicySha256())
                || !storedProof.draft().equals(job.draft())
                || !storedProof.draftSha256().equals(job.draftSha256())
                || !storedProof.publicationRequestId().equals(job.id())
                || !job.publishedAt().equals(evidence.publishedAt())) {
            throw new SecurityException("Published job does not match retained approval proof");
        }
        PolicyProposalDocument proposal = mongo.findById(job.proposalId(), PolicyProposalDocument.class);
        Query historyQuery = Query.query(Criteria.where("proposalId").is(job.proposalId())
                .and("action").is("PUBLISH_POLICY").and("operationId").is("publish:" + job.id()));
        PolicyGovernanceHistoryDocument history = mongo.findOne(historyQuery, PolicyGovernanceHistoryDocument.class);
        if (proposal == null || proposal.status() != PolicyGovernanceState.PUBLISHED
                || !job.id().equals(proposal.publicationRequestId())
                || !job.draftSha256().equals(proposal.draftSha256())
                || !job.decidedAt().equals(proposal.decidedAt())
                || history == null || mongo.count(historyQuery, PolicyGovernanceHistoryDocument.class) != 1
                || history.toStatus() != PolicyGovernanceState.PUBLISHED
                || !job.draftSha256().equals(history.draftSha256())
                || !job.approvalEvidenceReference().equals(history.approvalEvidenceReference())) {
            throw new SecurityException("Publication proposal or history linkage is invalid");
        }
        Verified verified = verify(job, storedProof, false);
        var authorization = verified.authorization();
        if (authorization.approvalTrustGeneration() < evidence.approvalTrustGeneration()) {
            throw new SecurityException("Current approval trust generation rolled back");
        }
        String key = ModelPromotionPolicyDocument.policyKey(policy.content().governanceScope(),
                policy.content().intendedUse(), policy.content().policyId(), policy.content().revision());
        if (!storedProof.equals(verified.artifact())
                || !policy.content().equals(job.draft().approvedContent(job.decidedAt()))
                || !evidence.policyKey().equals(key)
                || !evidence.policyCanonicalSha256().equals(policy.canonicalSha256())
                || !evidence.approvalProofSha256().equals(GovernanceCanonicalJson.sha256(mapper, storedProof))
                || !evidence.approverIdentity().equals(authorization.approverIdentity())
                || !evidence.approverKeyId().equals(storedProof.approval().evidence().credentialId())
                || !evidence.approverPublicKeySha256().equals(authorization.approverPublicKeySha256())
                || !evidence.publisherIdentity().equals(authorization.publisherIdentity())
                || !evidence.publisherKeyId().equals(job.draft().publisherKeyId())
                || !evidence.publisherPublicKeySha256().equals(authorization.publisherPublicKeySha256())
                || evidence.publisherMandate() != InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER
                || !evidence.mandatedScope().equals(authorization.governanceScope())
                || evidence.mandatedUse() != authorization.intendedUse()
                || !evidence.issuerIdentity().equals(authorization.issuerIdentity())
                || !evidence.issuerKeyId().equals(authorization.keyId())
                || !evidence.issuerPublicKeySha256().equals(authorization.issuerPublicKeySha256())
                || !evidence.policySignature().equals(policy.authorizationEvidence().signature())) {
            throw new SecurityException("Published online approval chain does not bind this signed policy");
        }
        return verified;
    }

    private OnlinePolicyApprovalArtifact.ActionProof verifyAction(PolicyPublicationJobDocument job,
            String reference, String action, String decision, String actorId, Instant acceptedAt,
            long expectedVersion, OnlinePolicyApprovalArtifact.ActionProof retained,
            boolean newPublication) throws Exception {
        if (!reference.startsWith("webauthn:action:")) {
            throw new SecurityException("Action proof reference is not WebAuthn");
        }
        String id = reference.substring("webauthn:".length());
        PolicyWebAuthnEvidence evidence = mongo.findById(id, PolicyWebAuthnEvidence.class);
        PolicyWebAuthnChallenge challenge = mongo.findById(id, PolicyWebAuthnChallenge.class);
        if (evidence == null || challenge == null || !challenge.used()
                || !action.equals(evidence.action()) || !action.equals(challenge.kind())
                || !decision.equals(challenge.decision()) || !actorId.equals(evidence.actorId())
                || !actorId.equals(challenge.actorId()) || !job.proposalId().equals(evidence.proposalId())
                || !job.proposalId().equals(challenge.proposalId())
                || !job.draftSha256().equals(evidence.draftSha256())
                || !job.draftSha256().equals(challenge.draftSha256())
                || !acceptedAt.equals(evidence.acceptedAt())
                || !acceptedAt.isBefore(challenge.expiresAt())
                || challenge.expectedVersion() != expectedVersion
                || !evidence.operationId().equals(challenge.operationId())
                || !evidence.issuer().equals(challenge.issuer())
                || !evidence.subject().equals(challenge.subject())
                || !challenge.policyId().equals(job.draft().policyId())
                || challenge.revision() != job.draft().revision()
                || !challenge.institutionalScope().equals(job.draft().governanceScope().institutionalScope())
                || !challenge.environment().equals(job.draft().governanceScope().environment().name())
                || !challenge.intendedUse().equals(job.draft().intendedUse().name())
                || !evidence.assertionSha256().equals(GovernanceCanonicalJson.sha256(
                        evidence.assertionResponseJson().getBytes(StandardCharsets.UTF_8)))) {
            throw new SecurityException("Action proof or committed snapshot changed");
        }
        PolicyWebAuthnCredential credential = mongo.findById(evidence.credentialId(),
                PolicyWebAuthnCredential.class);
        if (credential == null || !actorId.equals(credential.actorId())
                || !evidence.issuer().equals(credential.issuer())
                || !evidence.subject().equals(credential.subject())
                || credential.signatureCount() < evidence.signatureCount()
                || (newPublication && !credential.canSignNewAction())
                || (!newPublication && !credential.canSupportPublishedApproval())) {
            throw new SecurityException("Current WebAuthn credential is unavailable");
        }
        if (retained != null && (!retained.challenge().equals(challenge)
                || !retained.evidence().equals(evidence)
                || !retained.publicKeyCose().equals(credential.publicKeyCose())
                || !retained.userHandle().equals(credential.userHandle()))) {
            throw new SecurityException("Retained WebAuthn approval material changed");
        }
        String expectedChallenge = GovernanceCanonicalJson.sha256(mapper, contract(job, challenge, evidence));
        AssertionRequest request = AssertionRequest.fromJson(challenge.requestJson());
        if (!request.getPublicKeyCredentialRequestOptions().getChallenge().getHex().equals(expectedChallenge)
                || !expectedChallenge.equals(evidence.challengeSha256())) {
            throw new SecurityException("Signed action challenge does not bind the frozen publication draft");
        }
        RelyingParty relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(rpId).name("Model policy governance").build())
                .credentialRepository(new HistoricalCredentialRepository(credential, evidence.signatureCount()))
                .origins(Set.of(origin)).allowOriginPort(false).allowOriginSubdomain(false)
                .validateSignatureCounter(true).build();
        var result = relyingParty.finishAssertion(FinishAssertionOptions.builder().request(request)
                .response(PublicKeyCredential.parseAssertionResponseJson(evidence.assertionResponseJson())).build());
        if (!result.isSuccess() || !result.isUserVerified() || !result.isSignatureCounterValid()
                || !actorId.equals(result.getUsername())
                || !evidence.credentialId().equals(result.getCredentialId().getBase64Url())
                || result.getSignatureCount() != evidence.signatureCount()) {
            throw new SecurityException("WebAuthn assertion failed independent cryptographic verification");
        }
        return new OnlinePolicyApprovalArtifact.ActionProof(challenge, evidence,
                credential.publicKeyCose(), credential.userHandle());
    }

    private Map<String, Object> contract(PolicyPublicationJobDocument job,
            PolicyWebAuthnChallenge challenge, PolicyWebAuthnEvidence evidence) {
        return PolicyWebAuthnActionContract.build(challenge.id(), challenge.nonce(), challenge.expiresAt(),
                evidence.issuer(), evidence.subject(), evidence.actorId(), evidence.operationId(),
                evidence.action(), challenge.decision(), challenge.rationale(), job.proposalId(),
                challenge.expectedVersion(), job.draftSha256(), job.draft().policyId(),
                job.draft().revision(), job.draft().governanceScope().institutionalScope(),
                job.draft().governanceScope().environment().name(), job.draft().intendedUse().name());
    }

    private InternalPolicyApprovalTrustRegistry.TrustedKey mandate(String identity, String keyId,
            InternalPolicyApprovalTrustRegistry.Role role, ModelPromotionPolicyDraft draft, Instant now) {
        var key = approvalTrust.find(identity, keyId);
        if (key == null || key.status() != ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE
                || !key.roles().contains(role)
                || !key.institutionalScopes().contains(draft.governanceScope().institutionalScope())
                || !key.environments().contains(draft.governanceScope().environment())
                || !key.intendedUses().contains(draft.intendedUse())
                || now.isBefore(key.validFrom()) || !now.isBefore(key.validUntil())) {
            throw new SecurityException("Current publisher or issuer mandate is unavailable");
        }
        return key;
    }

    record Verified(OnlinePolicyApprovalArtifact artifact,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization) { }

    private static final class HistoricalCredentialRepository implements CredentialRepository {
        private final PolicyWebAuthnCredential credential;
        private final long count;

        private HistoricalCredentialRepository(PolicyWebAuthnCredential credential, long count) {
            this.credential = credential;
            this.count = count;
        }

        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            return credential.actorId().equals(username) ? Set.of(PublicKeyCredentialDescriptor.builder()
                    .id(bytes(credential.id())).build()) : Set.of();
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            return credential.actorId().equals(username) ? Optional.of(bytes(credential.userHandle()))
                    : Optional.empty();
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            return credential.userHandle().equals(userHandle.getBase64Url())
                    ? Optional.of(credential.actorId()) : Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray userHandle) {
            return credential.id().equals(id.getBase64Url())
                    && credential.userHandle().equals(userHandle.getBase64Url())
                    ? Optional.of(registered()) : Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray id) {
            return credential.id().equals(id.getBase64Url()) ? Set.of(registered()) : Set.of();
        }

        private RegisteredCredential registered() {
            return RegisteredCredential.builder().credentialId(bytes(credential.id()))
                    .userHandle(bytes(credential.userHandle()))
                    .publicKeyCose(bytes(credential.publicKeyCose()))
                    .signatureCount(count == 0 ? 0 : count - 1).build();
        }

        private ByteArray bytes(String encoded) {
            return new ByteArray(Base64.getUrlDecoder().decode(encoded));
        }
    }
}
