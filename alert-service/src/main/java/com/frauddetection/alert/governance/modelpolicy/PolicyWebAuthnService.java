package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceContracts.*;

/** OIDC-bound WebAuthn ceremonies. The assertion and policy transition commit in one Mongo transaction. */
@Service
public class PolicyWebAuthnService {
    private static final int CHALLENGE_SECONDS = 120;
    private static final int MAX_RESPONSE_JSON = 65536;
    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final PolicyWebAuthnCredentialRepository credentials;
    private final PolicyCredentialEnrollmentGrantService enrollmentGrants;
    private final PolicyGovernanceService governance;
    private final MongoPolicyGovernanceStore governanceStore;
    private final ObjectMapper mapper;
    private final RelyingParty relyingParty;
    private final SecureRandom random = new SecureRandom();

    public PolicyWebAuthnService(MongoTemplate mongo, MongoTransactionManager manager,
            PolicyWebAuthnCredentialRepository credentials, PolicyGovernanceService governance,
            MongoPolicyGovernanceStore governanceStore, PolicyCredentialEnrollmentGrantService enrollmentGrants,
            ObjectMapper mapper, Environment environment,
            @Value("${app.model-policy-webauthn.rp-id:localhost}") String rpId,
            @Value("${app.model-policy-webauthn.origin:http://localhost:4173}") String origin) {
        this.mongo = mongo;
        this.transaction = new TransactionTemplate(manager);
        this.credentials = credentials;
        this.enrollmentGrants = enrollmentGrants;
        this.governance = governance;
        this.governanceStore = governanceStore;
        this.mapper = mapper;
        validateOrigin(rpId, origin, environment);
        this.relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(rpId).name("Model policy governance").build())
                .credentialRepository(credentials).origins(Set.of(origin))
                .preferredPubkeyParams(List.of(PublicKeyCredentialParameters.ES256,
                        PublicKeyCredentialParameters.RS256))
                .allowOriginPort(false).allowOriginSubdomain(false)
                .validateSignatureCounter(true).build();
    }

    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(PolicyWebAuthnChallenge.class).ensureIndex(new Index()
                .on("actorId", Sort.Direction.ASC).on("expiresAt", Sort.Direction.ASC)
                .named("policy_webauthn_challenge_lookup_v1"));
        mongo.indexOps(PolicyWebAuthnChallenge.class).ensureIndex(new Index()
                .on("used", Sort.Direction.ASC).on("expiresAt", Sort.Direction.ASC)
                .named("policy_webauthn_unused_retention_v1"));
        mongo.indexOps(PolicyWebAuthnEvidence.class).ensureIndex(new Index()
                .on("operationId", Sort.Direction.ASC).unique()
                .named("policy_webauthn_evidence_operation_v1"));
    }

    /** Never TTL committed ceremonies: their challenge is part of the published approval proof. */
    @Scheduled(cron = "${app.model-policy-webauthn.unused-challenge-cleanup-cron:0 17 3 * * *}")
    void pruneExpiredUnusedChallenges() {
        mongo.remove(Query.query(Criteria.where("used").is(false)
                .and("expiresAt").lt(now().minusSeconds(7 * 24 * 3600))),
                PolicyWebAuthnChallenge.class);
    }

    public CeremonyOptions startRegistration(PolicyGovernanceActor actor, RegistrationIntent intent) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        requireInstitutionalIdentity(actor);
        if (intent == null) throw new AccessDeniedException("Protected enrollment grant required");
        var purpose = enrollmentPurpose(actor);
        var grant = enrollmentGrants.validate(actor, intent.grantToken(), purpose);
        UserIdentity user = UserIdentity.builder().name(actor.id()).displayName("Policy operator")
                .id(userHandle(actor)).build();
        var selection = AuthenticatorSelectionCriteria.builder()
                .userVerification(UserVerificationRequirement.REQUIRED).build();
        var options = relyingParty.startRegistration(StartRegistrationOptions.builder().user(user)
                .authenticatorSelection(selection).timeout(CHALLENGE_SECONDS * 1000L).build());
        try {
            String id = "registration:" + UUID.randomUUID();
            Instant expiresAt = now().plusSeconds(CHALLENGE_SECONDS);
            mongo.insert(new PolicyWebAuthnChallenge(id, "REGISTER", actor.id(), actor.issuer(),
                    actor.subject(), null, null, 0, null, null, 0, null, null, null, null,
                    nonce(), options.toJson(), expiresAt, false, null, grant.id(),
                    grant.purpose().name(), sha256(intent.grantToken())));
            return new CeremonyOptions(id, options.toCredentialsCreateJson(), expiresAt);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create WebAuthn registration options", failure);
        }
    }

    public CredentialSummary finishRegistration(PolicyGovernanceActor actor, FinishCeremony command) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        requireInstitutionalIdentity(actor);
        validateCeremonyCommand(command);
        var purpose = enrollmentPurpose(actor);
        var grant = enrollmentGrants.validate(actor, command.grantToken(), purpose);
        try {
            return transaction.execute(ignored -> {
                PolicyWebAuthnChallenge challenge = requireChallenge(command.challengeId(), actor, "REGISTER");
                if (!grant.id().equals(challenge.registrationGrantId())
                        || !purpose.name().equals(challenge.registrationGrantPurpose())
                        || !sha256(command.grantToken()).equals(challenge.registrationGrantSha256())
                        || enrollmentPurpose(actor) != purpose) {
                    throw new AccessDeniedException("Enrollment authorization or credential state changed");
                }
                try {
                    var options = PublicKeyCredentialCreationOptions.fromJson(challenge.requestJson());
                    var response = PublicKeyCredential.parseRegistrationResponseJson(command.responseJson());
                    RegistrationResult result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                            .request(options).response(response).build());
                    if (!result.isUserVerified()) throw new AccessDeniedException("User verification required");
                    String credentialId = result.getKeyId().getId().getBase64Url();
                    Instant acceptedAt = now();
                    if (!acceptedAt.isBefore(challenge.expiresAt())) {
                        throw new PolicyGovernanceConflictException("WebAuthn registration challenge expired");
                    }
                    String label = "Passkey " + credentialId.substring(0, Math.min(8, credentialId.length()));
                    consume(challenge);
                    enrollmentGrants.consume(grant, credentialId, sha256(command.responseJson()), acceptedAt);
                    mongo.insert(new PolicyWebAuthnCredential(credentialId, actor.id(), actor.issuer(),
                            actor.subject(), userHandle(actor).getBase64Url(),
                            result.getPublicKeyCose().getBase64Url(), result.getSignatureCount(), false,
                            acceptedAt, acceptedAt, "ACTIVE", purpose.name(), grant.id(),
                            grant.approvedBy(), grant.caseReference(), label));
                    return new CredentialSummary(credentialId, acceptedAt, acceptedAt,
                            "ACTIVE", label);
                } catch (AccessDeniedException | PolicyGovernanceConflictException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalArgumentException("Invalid WebAuthn registration response", failure);
                }
            });
        } catch (DuplicateKeyException duplicate) {
            throw new PolicyGovernanceConflictException("Credential or registration challenge already used");
        }
    }

    private PolicyCredentialEnrollmentGrantDocument.Purpose enrollmentPurpose(PolicyGovernanceActor actor) {
        if (!mongo.exists(Query.query(Criteria.where("actorId").is(actor.id())
                .and("issuer").is(actor.issuer()).and("subject").is(actor.subject())),
                PolicyWebAuthnCredential.class)) {
            return PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST;
        }
        if (mongo.exists(Query.query(Criteria.where("actorId").is(actor.id())
                .and("issuer").is(actor.issuer()).and("subject").is(actor.subject())
                .and("revoked").is(false).and("status").is("ACTIVE")), PolicyWebAuthnCredential.class)) {
            return PolicyCredentialEnrollmentGrantDocument.Purpose.ADDITIONAL;
        }
        return mongo.exists(Query.query(Criteria.where("actorId").is(actor.id())
                .and("issuer").is(actor.issuer()).and("subject").is(actor.subject())
                .and("status").is("COMPROMISED")), PolicyWebAuthnCredential.class)
                ? PolicyCredentialEnrollmentGrantDocument.Purpose.COMPROMISE_RECOVERY
                : PolicyCredentialEnrollmentGrantDocument.Purpose.LOST_DEVICE_RECOVERY;
    }

    public List<CredentialSummary> credentials(PolicyGovernanceActor actor) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        return credentials.allForActor(actor.id()).stream()
                .map(credential -> new CredentialSummary(credential.id(), credential.createdAt(),
                        credential.updatedAt(), credential.status(), credential.label()))
                .sorted((left, right) -> left.credentialId().compareTo(right.credentialId()))
                .toList();
    }

    public List<PolicyCredentialSecurityEventDocument> securityEvents(PolicyGovernanceActor actor) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        requireInstitutionalIdentity(actor);
        return List.copyOf(mongo.find(Query.query(Criteria.where("targetActorId").is(actor.id()))
                .with(Sort.by(Sort.Direction.DESC, "occurredAt")).limit(25),
                PolicyCredentialSecurityEventDocument.class));
    }

    public CredentialSummary renameCredential(PolicyGovernanceActor actor, String credentialId, String label) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        requireInstitutionalIdentity(actor);
        if (label == null || !label.matches("^[\\p{L}\\p{N} ._-]{1,64}$")) {
            throw new IllegalArgumentException("Credential label must be 1-64 readable characters");
        }
        return transaction.execute(ignored -> {
            var credential = credentials.find(credentialId);
            if (credential == null || !actor.id().equals(credential.actorId())
                    || !actor.issuer().equals(credential.issuer())
                    || !actor.subject().equals(credential.subject())) {
                throw new AccessDeniedException("Credential belongs to another actor");
            }
            Instant changedAt = now();
            var changed = mongo.findAndModify(Query.query(Criteria.where("_id").is(credentialId)
                            .and("actorId").is(actor.id()).and("updatedAt").is(credential.updatedAt())),
                    new Update().set("label", label.trim()).set("updatedAt", changedAt),
                    FindAndModifyOptions.options().returnNew(true), PolicyWebAuthnCredential.class);
            if (changed == null) throw new PolicyGovernanceConflictException("Credential changed concurrently");
            mongo.insert(new PolicyCredentialSecurityEventDocument("credential-label:" + UUID.randomUUID(),
                    "CREDENTIAL_LABEL_UPDATED", actor.id(), actor.issuer(), actor.subject(),
                    actor.id(), null, null, credentialId, changedAt));
            return new CredentialSummary(changed.id(), changed.createdAt(), changed.updatedAt(),
                    changed.status(), changed.label());
        });
    }

    public CeremonyOptions startCredentialRevocation(PolicyGovernanceActor actor, String credentialId,
            CredentialActionIntent intent) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        requireInstitutionalIdentity(actor);
        if (intent == null || intent.operationId() == null
                || !intent.operationId().matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Valid credential operation ID required");
        }
        requireOwnedActiveCredential(actor, credentialId);
        String id = "credential-revoke:" + intent.operationId();
        Instant expiresAt = now().plusSeconds(CHALLENGE_SECONDS);
        String nonce = nonce();
        String digest = GovernanceCanonicalJson.sha256(mapper,
                credentialActionContract(id, nonce, expiresAt, actor, credentialId));
        var initial = relyingParty.startAssertion(StartAssertionOptions.builder()
                .username(actor.id()).userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(CHALLENGE_SECONDS * 1000L).build());
        var request = initial.toBuilder().publicKeyCredentialRequestOptions(initial
                .getPublicKeyCredentialRequestOptions().toBuilder()
                .challenge(new ByteArray(HexFormat.of().parseHex(digest))).build()).build();
        try {
            mongo.insert(new PolicyWebAuthnChallenge(id, "CREDENTIAL_REVOKE", actor.id(), actor.issuer(),
                    actor.subject(), intent.operationId(), credentialId, 0, null, null, 0, null, null,
                    null, null, nonce, request.toJson(), expiresAt, false, null, null, null, null));
            return new CeremonyOptions(id, request.toCredentialsGetJson(), expiresAt);
        } catch (DuplicateKeyException duplicate) {
            throw new PolicyGovernanceConflictException("Credential operation ID already reserved");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create credential revocation challenge", failure);
        }
    }

    public void finishCredentialRevocation(PolicyGovernanceActor actor, String credentialId,
            FinishAction command) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        requireInstitutionalIdentity(actor);
        if (command == null || command.operationId() == null
                || !command.operationId().matches("^[A-Za-z0-9._:-]{1,128}$")
                || !Objects.equals(command.challengeId(), "credential-revoke:" + command.operationId())
                || command.responseJson() == null || command.responseJson().isBlank()
                || command.responseJson().length() > MAX_RESPONSE_JSON) {
            throw new IllegalArgumentException("Complete credential revocation assertion required");
        }
        transaction.executeWithoutResult(ignored -> {
            var challenge = requireChallenge(command.challengeId(), actor, "CREDENTIAL_REVOKE");
            if (!credentialId.equals(challenge.proposalId())
                    || !command.operationId().equals(challenge.operationId())) {
                throw new AccessDeniedException("Credential revocation target changed");
            }
            try {
                var request = AssertionRequest.fromJson(challenge.requestJson());
                String digest = GovernanceCanonicalJson.sha256(mapper, credentialActionContract(challenge.id(),
                        challenge.nonce(), challenge.expiresAt(), actor, credentialId));
                if (!digest.equals(request.getPublicKeyCredentialRequestOptions().getChallenge().getHex())) {
                    throw new PolicyGovernanceConflictException("Credential revocation contract changed");
                }
                var response = PublicKeyCredential.parseAssertionResponseJson(command.responseJson());
                var result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                        .request(request).response(response).build());
                if (!result.isSuccess() || !result.isUserVerified()
                        || !result.isSignatureCounterValid() || !actor.id().equals(result.getUsername())
                        || !userHandle(actor).equals(result.getUserHandle())) {
                    throw new AccessDeniedException("Trusted authenticator assertion required");
                }
                String provingId = result.getCredentialId().getBase64Url();
                var proving = requireOwnedActiveCredential(actor, provingId);
                requireOwnedActiveCredential(actor, credentialId);
                Instant acceptedAt = now();
                if (!acceptedAt.isBefore(challenge.expiresAt())) {
                    throw new PolicyGovernanceConflictException("Credential revocation challenge expired");
                }
                consume(challenge);
                var query = Query.query(Criteria.where("_id").is(provingId).and("actorId").is(actor.id())
                        .and("revoked").is(false).and("signatureCount").is(proving.signatureCount()));
                var update = new Update().set("signatureCount", result.getSignatureCount())
                        .set("updatedAt", acceptedAt);
                if (provingId.equals(credentialId)) update.set("revoked", true).set("status", "REVOKED");
                if (mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
                        PolicyWebAuthnCredential.class) == null) {
                    throw new PolicyGovernanceConflictException("Proving credential changed");
                }
                if (!provingId.equals(credentialId)) {
                    var targetQuery = Query.query(Criteria.where("_id").is(credentialId)
                            .and("actorId").is(actor.id()).and("revoked").is(false));
                    if (mongo.findAndModify(targetQuery, new Update().set("revoked", true)
                            .set("status", "REVOKED").set("updatedAt", acceptedAt),
                            FindAndModifyOptions.options().returnNew(true),
                            PolicyWebAuthnCredential.class) == null) {
                        throw new PolicyGovernanceConflictException("Target credential changed");
                    }
                }
                mongo.insert(new PolicyCredentialSecurityEventDocument("credential-revoke:" + command.operationId(),
                        "CREDENTIAL_REVOKED", actor.id(), actor.issuer(), actor.subject(), actor.id(), null,
                        challenge.id(), credentialId, acceptedAt));
            } catch (AccessDeniedException | PolicyGovernanceConflictException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalArgumentException("Invalid credential revocation assertion", failure);
            }
        });
    }

    public void protectedCredentialRevocation(PolicyGovernanceActor actor, String credentialId,
            ProtectedRevocation command) {
        require(actor, ModelPolicyAuthority.CREDENTIAL_ENROLL);
        requireInstitutionalIdentity(actor);
        if (command == null || command.grantToken() == null) {
            throw new AccessDeniedException("Independent revocation authorization required");
        }
        var purpose = command.compromised()
                ? PolicyCredentialEnrollmentGrantDocument.Purpose.COMPROMISE_REVOCATION
                : PolicyCredentialEnrollmentGrantDocument.Purpose.LOST_DEVICE_REVOCATION;
        var grant = enrollmentGrants.validate(actor, command.grantToken(), purpose);
        transaction.executeWithoutResult(ignored -> {
            requireOwnedActiveCredential(actor, credentialId);
            var current = enrollmentGrants.validate(actor, command.grantToken(), purpose);
            if (!grant.id().equals(current.id())) {
                throw new AccessDeniedException("Revocation grant changed");
            }
            Instant acceptedAt = now();
            var query = Query.query(Criteria.where("_id").is(credentialId).and("actorId").is(actor.id())
                    .and("issuer").is(actor.issuer()).and("subject").is(actor.subject())
                    .and("revoked").is(false));
            var updated = mongo.findAndModify(query, new Update().set("revoked", true)
                    .set("status", command.compromised() ? "COMPROMISED" : "LOST")
                    .set("updatedAt", acceptedAt), FindAndModifyOptions.options().returnNew(true),
                    PolicyWebAuthnCredential.class);
            if (updated == null) throw new PolicyGovernanceConflictException("Credential already revoked");
            enrollmentGrants.consumeRevocation(current, credentialId, acceptedAt);
        });
    }

    public CeremonyOptions startBoundAssertion(PolicyGovernanceActor actor, String namespace,
            String operationId, Map<String, Object> baseContract) {
        requireInstitutionalIdentity(actor);
        if (namespace == null || !namespace.matches("^[a-z-]{1,40}$")
                || operationId == null || !operationId.matches("^[A-Za-z0-9._:-]{1,128}$")
                || baseContract == null || !baseContract.containsKey("domain")
                || credentials.activeForActor(actor.id()).isEmpty()) {
            throw new AccessDeniedException("Bound assertion requires a trusted registered authenticator");
        }
        String id = namespace + ":" + operationId;
        String nonce = nonce();
        Instant expiresAt = now().plusSeconds(CHALLENGE_SECONDS);
        String digest = GovernanceCanonicalJson.sha256(mapper,
                boundContract(id, nonce, expiresAt, actor, baseContract));
        var initial = relyingParty.startAssertion(StartAssertionOptions.builder().username(actor.id())
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(CHALLENGE_SECONDS * 1000L).build());
        var request = initial.toBuilder().publicKeyCredentialRequestOptions(initial
                .getPublicKeyCredentialRequestOptions().toBuilder()
                .challenge(new ByteArray(HexFormat.of().parseHex(digest))).build()).build();
        try {
            mongo.insert(new GovernanceWebAuthnBoundChallenge(id, namespace, actor.id(), actor.issuer(),
                    actor.subject(), digest, nonce, request.toJson(), expiresAt, false));
            return new CeremonyOptions(id, request.toCredentialsGetJson(), expiresAt);
        } catch (DuplicateKeyException duplicate) {
            throw new PolicyGovernanceConflictException("Bound action operation ID already reserved");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create bound WebAuthn assertion", failure);
        }
    }

    /** Must be called inside the caller's Mongo transaction, together with its domain mutation and audit. */
    public VerifiedBoundAssertion acceptBoundAssertion(PolicyGovernanceActor actor, String namespace,
            String challengeId, String responseJson, Map<String, Object> baseContract) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bound assertion must commit with its governance mutation");
        }
        requireInstitutionalIdentity(actor);
        if (responseJson == null || responseJson.isBlank() || responseJson.length() > MAX_RESPONSE_JSON) {
            throw new IllegalArgumentException("Complete WebAuthn assertion required");
        }
        var challenge = mongo.findById(challengeId, GovernanceWebAuthnBoundChallenge.class);
        if (challenge == null || challenge.used() || !namespace.equals(challenge.namespace())
                || !actor.id().equals(challenge.actorId()) || !actor.issuer().equals(challenge.issuer())
                || !actor.subject().equals(challenge.subject()) || !now().isBefore(challenge.expiresAt())) {
            throw new AccessDeniedException("Bound assertion challenge unavailable for this actor");
        }
        String digest = GovernanceCanonicalJson.sha256(mapper,
                boundContract(challenge.id(), challenge.nonce(), challenge.expiresAt(), actor, baseContract));
        if (!digest.equals(challenge.contractSha256())) {
            throw new PolicyGovernanceConflictException("Bound action contract changed");
        }
        try {
            var request = AssertionRequest.fromJson(challenge.requestJson());
            if (!digest.equals(request.getPublicKeyCredentialRequestOptions().getChallenge().getHex())) {
                throw new PolicyGovernanceConflictException("Bound assertion challenge changed");
            }
            var response = PublicKeyCredential.parseAssertionResponseJson(responseJson);
            var result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request).response(response).build());
            if (!result.isSuccess() || !result.isUserVerified() || !result.isSignatureCounterValid()
                    || !actor.id().equals(result.getUsername()) || !userHandle(actor).equals(result.getUserHandle())) {
                throw new AccessDeniedException("WebAuthn assertion does not prove this action");
            }
            String credentialId = result.getCredentialId().getBase64Url();
            var credential = requireOwnedActiveCredential(actor, credentialId);
            Instant acceptedAt = now();
            if (!acceptedAt.isBefore(challenge.expiresAt())) {
                throw new PolicyGovernanceConflictException("Bound assertion challenge expired");
            }
            var claim = Query.query(Criteria.where("_id").is(challenge.id()).and("used").is(false));
            if (mongo.findAndModify(claim, new Update().set("used", true),
                    FindAndModifyOptions.options().returnNew(true), GovernanceWebAuthnBoundChallenge.class) == null) {
                throw new PolicyGovernanceConflictException("Bound assertion challenge already used");
            }
            var counter = Query.query(Criteria.where("_id").is(credentialId).and("actorId").is(actor.id())
                    .and("revoked").is(false).and("signatureCount").is(credential.signatureCount()));
            if (mongo.findAndModify(counter, new Update().set("signatureCount", result.getSignatureCount())
                    .set("updatedAt", acceptedAt), FindAndModifyOptions.options().returnNew(true),
                    PolicyWebAuthnCredential.class) == null) {
                throw new PolicyGovernanceConflictException("Credential revoked or counter changed");
            }
            return new VerifiedBoundAssertion(challenge.id(), credentialId, digest,
                    sha256(responseJson), result.getSignatureCount(), acceptedAt);
        } catch (AccessDeniedException | PolicyGovernanceConflictException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid bound WebAuthn assertion", failure);
        }
    }

    private Map<String, Object> boundContract(String id, String nonce, Instant expiresAt,
            PolicyGovernanceActor actor, Map<String, Object> base) {
        Map<String, Object> contract = new LinkedHashMap<>(base);
        contract.put("challengeId", id);
        contract.put("nonce", nonce);
        contract.put("expiresAt", expiresAt.toString());
        contract.put("actorId", actor.id());
        contract.put("issuer", actor.issuer());
        contract.put("subject", actor.subject());
        return contract;
    }

    private PolicyWebAuthnCredential requireOwnedActiveCredential(PolicyGovernanceActor actor, String id) {
        var credential = id == null ? null : credentials.find(id);
        if (credential == null || !credential.canSignNewAction() || !actor.id().equals(credential.actorId())
                || !actor.issuer().equals(credential.issuer())
                || !actor.subject().equals(credential.subject())
                || !userHandle(actor).getBase64Url().equals(credential.userHandle())) {
            throw new AccessDeniedException("Active credential belonging to this identity required");
        }
        return credential;
    }

    private Map<String, Object> credentialActionContract(String id, String nonce, Instant expiresAt,
            PolicyGovernanceActor actor, String credentialId) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", "com.frauddetection.model-policy.credential-revoke.v1");
        contract.put("challengeId", id);
        contract.put("nonce", nonce);
        contract.put("expiresAt", expiresAt.toString());
        contract.put("actorId", actor.id());
        contract.put("issuer", actor.issuer());
        contract.put("subject", actor.subject());
        contract.put("credentialId", credentialId);
        contract.put("action", "CREDENTIAL_REVOKE");
        return contract;
    }

    public CeremonyOptions startAction(PolicyGovernanceActor actor, String proposalId,
            ActionIntent intent, String route) {
        requireInstitutionalIdentity(actor);
        if (intent == null || intent.operationId() == null
                || !intent.operationId().matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Valid operation ID required");
        }
        String action = action(route, intent.decision());
        String rationale = intent.rationale() == null ? null : intent.rationale().trim();
        if (!"POLICY_SUBMIT".equals(action)
                && (rationale == null || rationale.isBlank() || rationale.length() > 1000)) {
            throw new IllegalArgumentException("Review rationale must contain 1 to 1000 characters");
        }
        if ("POLICY_SUBMIT".equals(action) && rationale != null) {
            throw new IllegalArgumentException("Submission cannot carry review rationale");
        }
        requireActionAuthority(actor, action);
        PolicyProposalDocument proposal = checkedProposal(actor, proposalId);
        validateProposalIntent(actor, proposal, intent.expectedVersion(), action);
        if (credentials.activeForActor(actor.id()).isEmpty()) {
            throw new PolicyGovernanceConflictException("Register a WebAuthn credential before this action");
        }
        String id = "action:" + intent.operationId();
        if (mongo.findById(id, PolicyWebAuthnChallenge.class) != null
                || mongo.findById(intent.operationId(), PolicyGovernanceOperationDocument.class) != null) {
            throw new PolicyGovernanceConflictException("Operation ID already reserved");
        }
        Instant expiresAt = now().plusSeconds(CHALLENGE_SECONDS);
        String nonce = nonce();
        var contract = contract(id, nonce, expiresAt, actor, proposal, intent.operationId(), action, rationale);
        ByteArray boundChallenge = new ByteArray(HexFormat.of().parseHex(
                GovernanceCanonicalJson.sha256(mapper, contract)));
        var initial = relyingParty.startAssertion(StartAssertionOptions.builder()
                .username(actor.id()).userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(CHALLENGE_SECONDS * 1000L).build());
        AssertionRequest request = initial.toBuilder().publicKeyCredentialRequestOptions(initial
                .getPublicKeyCredentialRequestOptions().toBuilder().challenge(boundChallenge).build()).build();
        try {
            mongo.insert(new PolicyWebAuthnChallenge(id, action, actor.id(), actor.issuer(), actor.subject(),
                    intent.operationId(), proposalId, intent.expectedVersion(), proposal.draftSha256(),
                    proposal.draft().policyId(), proposal.draft().revision(),
                    proposal.draft().governanceScope().institutionalScope(),
                    proposal.draft().governanceScope().environment().name(),
                    proposal.draft().intendedUse().name(), decisionFor(action), nonce,
                    request.toJson(), expiresAt, false, rationale, null, null, null));
            return new CeremonyOptions(id, request.toCredentialsGetJson(), expiresAt);
        } catch (DuplicateKeyException duplicate) {
            throw new PolicyGovernanceConflictException("Operation ID already reserved");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create WebAuthn assertion options", failure);
        }
    }

    public PolicyProposalDocument finishAction(PolicyGovernanceActor actor, String proposalId,
            FinishAction command, String route) {
        requireInstitutionalIdentity(actor);
        if (command == null || command.operationId() == null
                || !command.operationId().matches("^[A-Za-z0-9._:-]{1,128}$")
                || !Objects.equals(command.challengeId(), "action:" + command.operationId())
                || command.responseJson() == null || command.responseJson().isBlank()
                || command.responseJson().length() > MAX_RESPONSE_JSON) {
            throw new IllegalArgumentException("Complete WebAuthn assertion required");
        }
        PolicyWebAuthnChallenge challenge = mongo.findById(command.challengeId(), PolicyWebAuthnChallenge.class);
        if (challenge == null || !Objects.equals(challenge.operationId(), command.operationId())
                || !Objects.equals(challenge.proposalId(), proposalId)
                || !matchesRoute(challenge.kind(), route)) {
            throw new PolicyGovernanceConflictException("Unknown or mismatched policy action challenge");
        }
        requireActionAuthority(actor, challenge.kind());
        requireChallengeOwner(challenge, actor);
        checkedProposal(actor, proposalId);
        PolicyWebAuthnEvidence prior = mongo.findById(challenge.id(), PolicyWebAuthnEvidence.class);
        if (prior != null) return recoverCommitted(actor, challenge, command, prior);
        try {
            return transaction.execute(ignored -> acceptInTransaction(actor, proposalId, command, challenge.kind()));
        } catch (RuntimeException failure) {
            PolicyWebAuthnEvidence committed = mongo.findById(challenge.id(), PolicyWebAuthnEvidence.class);
            if (committed != null) return recoverCommitted(actor,
                    mongo.findById(challenge.id(), PolicyWebAuthnChallenge.class), command, committed);
            throw failure;
        }
    }

    private PolicyProposalDocument acceptInTransaction(PolicyGovernanceActor actor, String proposalId,
            FinishAction command, String action) {
        PolicyWebAuthnChallenge challenge = requireChallenge(command.challengeId(), actor, action);
        if (!decisionFor(action).equals(challenge.decision())
                || !command.operationId().equals(challenge.operationId())) {
            throw new PolicyGovernanceConflictException("Policy decision or operation changed");
        }
        PolicyProposalDocument proposal = checkedProposal(actor, proposalId);
        validateProposalIntent(actor, proposal, challenge.expectedVersion(), action);
        validateSnapshot(challenge, proposal, actor);
        try {
            AssertionRequest request = AssertionRequest.fromJson(challenge.requestJson());
            String expectedChallenge = GovernanceCanonicalJson.sha256(mapper, contract(challenge.id(),
                    challenge.nonce(), challenge.expiresAt(), actor, proposal, challenge.operationId(), action,
                    challenge.rationale()));
            if (!request.getPublicKeyCredentialRequestOptions().getChallenge().getHex()
                    .equals(expectedChallenge)) {
                throw new PolicyGovernanceConflictException("Policy action contract changed");
            }
            var response = PublicKeyCredential.parseAssertionResponseJson(command.responseJson());
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request).response(response).build());
            if (!result.isSuccess() || !result.isUserVerified()
                    || !actor.id().equals(result.getUsername())
                    || !userHandle(actor).equals(result.getUserHandle())
                    || !result.isSignatureCounterValid()) {
                throw new AccessDeniedException("WebAuthn assertion does not prove this actor's action");
            }
            String credentialId = result.getCredentialId().getBase64Url();
            PolicyWebAuthnCredential credential = credentials.find(credentialId);
            if (credential == null || !credential.canSignNewAction() || !actor.id().equals(credential.actorId())
                    || !actor.issuer().equals(credential.issuer())
                    || !actor.subject().equals(credential.subject())
                    || !userHandle(actor).getBase64Url().equals(credential.userHandle())) {
                throw new AccessDeniedException("Credential owner or status changed");
            }
            Instant acceptedAt = now();
            if (!acceptedAt.isBefore(challenge.expiresAt())) {
                throw new PolicyGovernanceConflictException("WebAuthn action challenge expired");
            }
            consume(challenge);
            var counterQuery = Query.query(Criteria.where("_id").is(credentialId)
                    .and("actorId").is(actor.id()).and("revoked").is(false)
                    .and("signatureCount").is(credential.signatureCount()));
            var updatedCredential = mongo.findAndModify(counterQuery,
                    new Update().set("signatureCount", result.getSignatureCount())
                            .set("updatedAt", acceptedAt),
                    FindAndModifyOptions.options().returnNew(true), PolicyWebAuthnCredential.class);
            if (updatedCredential == null) {
                throw new PolicyGovernanceConflictException("Credential revoked or counter changed");
            }
            mongo.insert(new PolicyWebAuthnEvidence(challenge.id(), challenge.operationId(), proposalId,
                    action, actor.id(), actor.issuer(), actor.subject(), credentialId,
                    challenge.draftSha256(), sha256(command.responseJson()), command.responseJson(),
                    expectedChallenge,
                    result.getSignatureCount(), acceptedAt));
            if ("POLICY_SUBMIT".equals(action)) {
                return governance.submit(actor, proposalId,
                        new TransitionCommand(challenge.operationId(), challenge.expectedVersion()),
                        new VerifiedPolicyAction(actor.id(), proposalId, challenge.operationId(),
                                challenge.expectedVersion(), action, acceptedAt));
            }
            ReviewDecision decision = "POLICY_APPROVE".equals(action)
                    ? ReviewDecision.APPROVE : ReviewDecision.REJECT;
            return governance.review(actor, proposalId,
                    new ReviewCommand(challenge.operationId(), challenge.expectedVersion(), decision,
                            "webauthn:" + challenge.id()),
                    new VerifiedPolicyAction(actor.id(), proposalId, challenge.operationId(),
                            challenge.expectedVersion(), action, acceptedAt));
        } catch (AccessDeniedException | PolicyGovernanceConflictException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid WebAuthn assertion", failure);
        }
    }

    private PolicyProposalDocument recoverCommitted(PolicyGovernanceActor actor,
            PolicyWebAuthnChallenge challenge, FinishAction command, PolicyWebAuthnEvidence evidence) {
        if (!challenge.used() || !actor.id().equals(evidence.actorId())
                || !actor.issuer().equals(evidence.issuer()) || !actor.subject().equals(evidence.subject())
                || !challenge.operationId().equals(evidence.operationId())
                || !challenge.kind().equals(evidence.action())
                || !challenge.decision().equals(decisionFor(evidence.action()))
                || !challenge.draftSha256().equals(evidence.draftSha256())
                || !sha256(command.responseJson()).equals(evidence.assertionSha256())
                || !sha256(evidence.assertionResponseJson()).equals(evidence.assertionSha256())) {
            throw new PolicyGovernanceConflictException("Challenge replay has different intent or assertion");
        }
        PolicyWebAuthnCredential credential = credentials.find(evidence.credentialId());
        if (credential == null || !actor.id().equals(credential.actorId())) {
            throw new AccessDeniedException("Committed credential ownership unavailable");
        }
        PolicyGovernanceOperationDocument operation = mongo.findById(challenge.operationId(),
                PolicyGovernanceOperationDocument.class);
        if (operation == null || !actor.id().equals(operation.actorId())
                || !challenge.proposalId().equals(operation.proposalId())) {
            throw new PolicyGovernanceConflictException("Committed operation receipt unavailable");
        }
        return operation.result();
    }

    private PolicyWebAuthnChallenge requireChallenge(String id, PolicyGovernanceActor actor, String kind) {
        PolicyWebAuthnChallenge challenge = mongo.findById(id, PolicyWebAuthnChallenge.class);
        if (challenge == null || challenge.used() || !kind.equals(challenge.kind())
                || !now().isBefore(challenge.expiresAt())) {
            throw new PolicyGovernanceConflictException("WebAuthn challenge unavailable or expired");
        }
        requireChallengeOwner(challenge, actor);
        return challenge;
    }

    private void requireChallengeOwner(PolicyWebAuthnChallenge challenge, PolicyGovernanceActor actor) {
        if (!actor.id().equals(challenge.actorId()) || !actor.issuer().equals(challenge.issuer())
                || !actor.subject().equals(challenge.subject())) {
            throw new AccessDeniedException("WebAuthn challenge belongs to a different OIDC identity");
        }
    }

    private void consume(PolicyWebAuthnChallenge challenge) {
        var query = Query.query(Criteria.where("_id").is(challenge.id()).and("used").is(false));
        var updated = mongo.findAndModify(query, new Update().set("used", true),
                FindAndModifyOptions.options().returnNew(true), PolicyWebAuthnChallenge.class);
        if (updated == null) throw new PolicyGovernanceConflictException("WebAuthn challenge already used");
    }

    private PolicyProposalDocument checkedProposal(PolicyGovernanceActor actor, String proposalId) {
        PolicyProposalDocument proposal = governanceStore.find(proposalId);
        if (proposal == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy proposal not found");
        if (proposal.draft().governanceScope().environment() == ModelGovernanceEnvironment.PRODUCTION) {
            require(actor, ModelPolicyAuthority.PRODUCTION);
        }
        return proposal;
    }

    private void validateProposalIntent(PolicyGovernanceActor actor, PolicyProposalDocument proposal,
            long expectedVersion, String action) {
        if (proposal.version() != expectedVersion || !proposal.draft().canonicalSha256(mapper)
                .equals(proposal.draftSha256())) {
            throw new PolicyGovernanceConflictException("Policy draft version or SHA changed");
        }
        if ("POLICY_SUBMIT".equals(action)) {
            if (proposal.status() != PolicyGovernanceState.DRAFT
                    || !actor.id().equals(proposal.authorActorId())
                    || !now().isBefore(Instant.parse(proposal.draft().effectiveFromInclusive()))) {
                throw new PolicyGovernanceConflictException("Draft cannot be submitted by this actor");
            }
        } else if (proposal.status() != PolicyGovernanceState.SUBMITTED
                || actor.id().equals(proposal.authorActorId())
                || actor.id().equals(proposal.submittingActorId())
                || !actor.id().equals(proposal.draft().approverIdentity())) {
            throw new AccessDeniedException("Independent assigned approver required");
        }
    }

    private void validateSnapshot(PolicyWebAuthnChallenge challenge, PolicyProposalDocument proposal,
            PolicyGovernanceActor actor) {
        if (!challenge.draftSha256().equals(proposal.draftSha256())
                || !challenge.policyId().equals(proposal.draft().policyId())
                || challenge.revision() != proposal.draft().revision()
                || !challenge.institutionalScope().equals(proposal.draft().governanceScope().institutionalScope())
                || !challenge.environment().equals(proposal.draft().governanceScope().environment().name())
                || !challenge.intendedUse().equals(proposal.draft().intendedUse().name())
                || !challenge.actorId().equals(actor.id())) {
            throw new PolicyGovernanceConflictException("Policy action snapshot changed");
        }
    }

    private Map<String, Object> contract(String id, String nonce, Instant expiresAt,
            PolicyGovernanceActor actor, PolicyProposalDocument proposal, String operationId, String action,
            String rationale) {
        return PolicyWebAuthnActionContract.build(id, nonce, expiresAt, actor.issuer(), actor.subject(),
                actor.id(), operationId, action, decisionFor(action), rationale, proposal.id(),
                proposal.version(), proposal.draftSha256(), proposal.draft().policyId(),
                proposal.draft().revision(), proposal.draft().governanceScope().institutionalScope(),
                proposal.draft().governanceScope().environment().name(),
                proposal.draft().intendedUse().name());
    }

    private String action(String route, ReviewDecision decision) {
        if ("submit".equals(route) && decision == null) return "POLICY_SUBMIT";
        if ("review".equals(route) && decision == ReviewDecision.APPROVE) return "POLICY_APPROVE";
        if ("review".equals(route) && decision == ReviewDecision.REJECT) return "POLICY_REJECT";
        throw new IllegalArgumentException("Invalid policy action or decision");
    }

    private String decisionFor(String action) {
        return switch (action) {
            case "POLICY_SUBMIT" -> "SUBMITTED";
            case "POLICY_APPROVE" -> "APPROVED";
            case "POLICY_REJECT" -> "REJECTED";
            default -> throw new IllegalArgumentException("Unknown policy action");
        };
    }

    private boolean matchesRoute(String action, String route) {
        return "submit".equals(route) ? "POLICY_SUBMIT".equals(action)
                : "review".equals(route) && ("POLICY_APPROVE".equals(action)
                        || "POLICY_REJECT".equals(action));
    }

    private void requireActionAuthority(PolicyGovernanceActor actor, String action) {
        require(actor, switch (action) {
            case "POLICY_SUBMIT" -> ModelPolicyAuthority.SUBMIT;
            case "POLICY_APPROVE" -> ModelPolicyAuthority.APPROVE;
            case "POLICY_REJECT" -> ModelPolicyAuthority.REJECT;
            default -> throw new IllegalArgumentException("Unknown policy action");
        });
    }

    private void require(PolicyGovernanceActor actor, String authority) {
        if (actor == null || !actor.authorities().contains(authority)) {
            throw new AccessDeniedException("Explicit policy mandate required: " + authority);
        }
    }

    private void requireInstitutionalIdentity(PolicyGovernanceActor actor) {
        if (actor == null || actor.issuer() == null || actor.issuer().isBlank()
                || actor.subject() == null || actor.subject().isBlank()
                || !actor.id().matches("^oidc:[0-9a-f]{64}$")) {
            throw new AccessDeniedException("Trusted OIDC issuer and subject required");
        }
    }

    private void validateCeremonyCommand(FinishCeremony command) {
        if (command == null || command.challengeId() == null
                || !command.challengeId().matches("^registration:[0-9a-f-]{36}$")
                || command.responseJson() == null || command.responseJson().isBlank()
                || command.responseJson().length() > MAX_RESPONSE_JSON) {
            throw new IllegalArgumentException("Complete WebAuthn registration response required");
        }
    }

    private ByteArray userHandle(PolicyGovernanceActor actor) {
        return new ByteArray(HexFormat.of().parseHex(actor.id().substring(5)));
    }

    private Instant now() {
        return governanceStore.now();
    }

    private String nonce() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private void validateOrigin(String rpId, String origin, Environment environment) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid WebAuthn origin", invalid);
        }
        if (rpId == null || rpId.isBlank() || uri.getHost() == null
                || (!uri.getHost().equals(rpId) && !uri.getHost().endsWith("." + rpId))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || (!"https".equals(uri.getScheme())
                    && !("localhost".equals(rpId) && "http".equals(uri.getScheme())))) {
            throw new IllegalStateException("WebAuthn RP ID and exact origin must be secure and compatible");
        }
        for (String profile : environment.getActiveProfiles()) {
            if (("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile))
                    && ("localhost".equals(rpId) || !"https".equals(uri.getScheme()))) {
                throw new IllegalStateException("Production WebAuthn requires an explicit HTTPS RP origin");
            }
        }
    }

    public record CeremonyOptions(String challengeId, String optionsJson, Instant expiresAt) { }
    public record CredentialSummary(String credentialId, Instant createdAt, Instant updatedAt,
            String status, String label) { }
    public record RegistrationIntent(String grantToken) { }
    public record CredentialActionIntent(String operationId) { }
    public record ProtectedRevocation(String grantToken, boolean compromised) { }
    public record VerifiedBoundAssertion(String challengeId, String credentialId, String contractSha256,
            String assertionSha256, long signatureCount, Instant acceptedAt) { }

    /** Unforgeable in production call sites; created only after assertion and evidence commit preparation. */
    public static final class VerifiedPolicyAction {
        private final String actorId;
        private final String proposalId;
        private final String operationId;
        private final long expectedVersion;
        private final String action;
        private final Instant acceptedAt;

        private VerifiedPolicyAction(String actorId, String proposalId, String operationId,
                long expectedVersion, String action, Instant acceptedAt) {
            this.actorId = actorId;
            this.proposalId = proposalId;
            this.operationId = operationId;
            this.expectedVersion = expectedVersion;
            this.action = action;
            this.acceptedAt = acceptedAt;
        }

        void require(PolicyGovernanceActor actor, String proposalId, String operationId,
                long expectedVersion, String action) {
            if (!this.actorId.equals(actor.id()) || !this.proposalId.equals(proposalId)
                    || !this.operationId.equals(operationId) || this.expectedVersion != expectedVersion
                    || !this.action.equals(action) || acceptedAt == null) {
                throw new AccessDeniedException("Verified policy action does not bind this transition");
            }
        }

        Instant acceptedAt() { return acceptedAt; }
    }
    public record FinishCeremony(String challengeId, String responseJson, String grantToken) { }
    public record ActionIntent(String operationId, long expectedVersion, ReviewDecision decision,
            String rationale) { }
    public record FinishAction(String operationId, String challengeId, String responseJson) { }
}
