package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyDraft;
import com.mongodb.MongoException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** One transactional owner for proposal, operation receipt and append-only local history. */
@Repository
public class MongoPolicyGovernanceStore {
    private static final int MAX_TRANSIENT_ATTEMPTS = 3;
    private static final int LEGACY_HISTORY_LIMIT = 200;

    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public MongoPolicyGovernanceStore(MongoTemplate mongo, MongoTransactionManager transactionManager) {
        this(mongo, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    MongoPolicyGovernanceStore(MongoTemplate mongo, TransactionTemplate transaction, Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.clock = clock;
    }

    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(PolicyProposalDocument.class).ensureIndex(new Index()
                .on("draft.policyId", Sort.Direction.ASC)
                .on("draft.revision", Sort.Direction.ASC)
                .on("draft.governanceScope.institutionalScope", Sort.Direction.ASC)
                .on("draft.governanceScope.environment", Sort.Direction.ASC)
                .on("draft.intendedUse", Sort.Direction.ASC)
                .unique().named("model_policy_draft_identity_v1"));
        mongo.indexOps(PolicyProposalDocument.class).ensureIndex(new Index()
                .on("draft.governanceScope.environment", Sort.Direction.ASC)
                .on("updatedAt", Sort.Direction.DESC).on("_id", Sort.Direction.DESC)
                .named("model_policy_scoped_page_v1"));
        mongo.indexOps(PolicyGovernanceHistoryDocument.class).ensureIndex(new Index()
                .on("proposalId", Sort.Direction.ASC).on("version", Sort.Direction.ASC)
                .unique().named("model_policy_history_version_v1"));
        mongo.indexOps(PolicyGovernanceHistoryDocument.class).ensureIndex(new Index()
                .on("proposalId", Sort.Direction.ASC).on("committedAt", Sort.Direction.ASC)
                .named("model_policy_history_lookup_v1"));
        mongo.indexOps(PolicyPublicationJobDocument.class).ensureIndex(new Index()
                .on("draft.policyId", Sort.Direction.ASC)
                .on("draft.revision", Sort.Direction.ASC)
                .on("draft.governanceScope.institutionalScope", Sort.Direction.ASC)
                .on("draft.governanceScope.environment", Sort.Direction.ASC)
                .on("draft.intendedUse", Sort.Direction.ASC)
                .unique().named("model_policy_publication_identity_v1"));
        mongo.indexOps(PolicyWebAuthnEvidence.class).ensureIndex(new Index()
                .on("proposalId", Sort.Direction.ASC).on("action", Sort.Direction.ASC)
                .unique().named("model_policy_action_proof_v1"));
    }

    public PolicyProposalDocument find(String id) {
        return mongo.findById(id, PolicyProposalDocument.class);
    }

    public PolicyGovernanceOperationDocument operation(String operationId) {
        return mongo.findById(operationId, PolicyGovernanceOperationDocument.class);
    }

    public List<PolicyProposalDocument> list(int limit, boolean productionAllowed) {
        Query query = productionAllowed ? new Query()
                : Query.query(Criteria.where("draft.governanceScope.environment").ne("PRODUCTION"));
        return List.copyOf(mongo.find(query.with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                .limit(limit), PolicyProposalDocument.class));
    }

    public List<PolicyProposalDocument> page(int limit, boolean productionAllowed,
            PolicyGovernanceState status, String policyIdPrefix, Instant beforeUpdatedAt, String beforeId) {
        List<Criteria> filters = new ArrayList<>();
        if (!productionAllowed) filters.add(Criteria.where("draft.governanceScope.environment").ne("PRODUCTION"));
        if (status != null) filters.add(Criteria.where("status").is(status));
        if (policyIdPrefix != null && !policyIdPrefix.isBlank()) {
            filters.add(Criteria.where("draft.policyId").regex("^" + Pattern.quote(policyIdPrefix)));
        }
        if (beforeUpdatedAt != null) filters.add(new Criteria().orOperator(
                Criteria.where("updatedAt").lt(beforeUpdatedAt),
                new Criteria().andOperator(Criteria.where("updatedAt").is(beforeUpdatedAt),
                        Criteria.where("_id").lt(beforeId))));
        Query query = filters.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(
                filters.toArray(Criteria[]::new)));
        return List.copyOf(mongo.find(query.with(Sort.by(Sort.Direction.DESC, "updatedAt", "_id"))
                .limit(limit), PolicyProposalDocument.class));
    }

    public List<PolicyGovernanceHistoryDocument> historyPage(String id, int limit, Long afterVersion) {
        Criteria criteria = Criteria.where("proposalId").is(id);
        if (afterVersion != null) criteria.and("version").gt(afterVersion);
        return List.copyOf(mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.ASC, "version"))
                .limit(limit), PolicyGovernanceHistoryDocument.class));
    }

    public List<PolicyGovernanceHistoryDocument> history(String id) {
        return List.copyOf(mongo.find(Query.query(Criteria.where("proposalId").is(id))
                .with(Sort.by(Sort.Direction.ASC, "version")).limit(LEGACY_HISTORY_LIMIT),
                PolicyGovernanceHistoryDocument.class));
    }

    Map<String, String> reviewRationales(String proposalId, List<PolicyGovernanceHistoryDocument> events) {
        List<PolicyGovernanceHistoryDocument> reviewEvents = events.stream()
                .filter(event -> "REVIEW_APPROVE".equals(event.action()) || "REVIEW_REJECT".equals(event.action()))
                .toList();
        if (reviewEvents.isEmpty()) return Map.of();

        List<String> challengeIds = reviewEvents.stream().map(event -> "action:" + event.operationId())
                .distinct().toList();
        List<PolicyWebAuthnChallenge> challenges = mongo.find(
                Query.query(Criteria.where("_id").in(challengeIds)), PolicyWebAuthnChallenge.class);
        Map<String, PolicyWebAuthnChallenge> byOperationId = new HashMap<>();
        for (PolicyWebAuthnChallenge challenge : challenges) {
            if (challenge.operationId() != null) byOperationId.put(challenge.operationId(), challenge);
        }

        Map<String, String> rationales = new HashMap<>();
        for (PolicyGovernanceHistoryDocument event : reviewEvents) {
            PolicyWebAuthnChallenge challenge = byOperationId.get(event.operationId());
            if (challenge != null && challenge.used()
                    && proposalId.equals(challenge.proposalId())
                    && event.actorId().equals(challenge.actorId())
                    && event.draftSha256().equals(challenge.draftSha256())
                    && event.operationId().equals(challenge.operationId())
                    && challenge.rationale() != null) {
                rationales.put(event.operationId(), challenge.rationale());
            }
        }
        return Map.copyOf(rationales);
    }

    void requireVerifiedApproval(PolicyProposalDocument proposal) {
        String reference = proposal.approvalEvidenceReference();
        if (reference == null || !reference.startsWith("webauthn:action:")) {
            throw new PolicyGovernanceConflictException("Verified WebAuthn approval evidence is required");
        }
        PolicyWebAuthnEvidence evidence = mongo.findById(reference.substring("webauthn:".length()),
                PolicyWebAuthnEvidence.class);
        if (evidence == null || !"POLICY_APPROVE".equals(evidence.action())
                || !proposal.id().equals(evidence.proposalId())
                || !proposal.draftSha256().equals(evidence.draftSha256())
                || !proposal.reviewingActorId().equals(evidence.actorId())
                || !proposal.decidedAt().equals(evidence.acceptedAt())) {
            throw new PolicyGovernanceConflictException("Verified WebAuthn approval evidence is inconsistent");
        }
        PolicyWebAuthnChallenge challenge = mongo.findById(evidence.id(), PolicyWebAuthnChallenge.class);
        PolicyWebAuthnCredential credential = mongo.findById(evidence.credentialId(),
                PolicyWebAuthnCredential.class);
        if (challenge == null || !challenge.used() || !"POLICY_APPROVE".equals(challenge.kind())
                || !"APPROVED".equals(challenge.decision())
                || !proposal.id().equals(challenge.proposalId())
                || !proposal.draftSha256().equals(challenge.draftSha256())
                || !evidence.operationId().equals(challenge.operationId())
                || evidence.assertionResponseJson() == null
                || !evidence.assertionSha256().equals(GovernanceCanonicalJson.sha256(
                        evidence.assertionResponseJson().getBytes(StandardCharsets.UTF_8)))
                || credential == null || !credential.actorId().equals(evidence.actorId())
                || !credential.issuer().equals(evidence.issuer())
                || !credential.subject().equals(evidence.subject())) {
            throw new PolicyGovernanceConflictException("Verified WebAuthn approval chain is incomplete");
        }
    }

    public PolicyProposalDocument create(ModelPromotionPolicyDraft draft, String digest, String actor,
            String operationId, String commandSha256) {
        return execute(operationId, commandSha256, actor, () -> {
            Instant now = clock.instant();
            PolicyProposalDocument created = new PolicyProposalDocument(UUID.randomUUID().toString(), draft, digest,
                    PolicyGovernanceState.DRAFT, 0, actor, null, null, null, null, null, null, now, now);
            mongo.insert(created);
            record(created, null, "CREATE_DRAFT", actor, operationId, commandSha256, now);
            return created;
        });
    }

    public PolicyProposalDocument mutate(String id, String operationId, String commandSha256, String actor,
            String action, Function<PolicyProposalDocument, PolicyProposalDocument> transition) {
        return execute(operationId, commandSha256, actor, () -> {
            PolicyProposalDocument current = mongo.findById(id, PolicyProposalDocument.class);
            if (current == null) {
                throw new PolicyGovernanceConflictException("Policy proposal is unavailable");
            }
            PolicyProposalDocument next = transition.apply(current);
            if (!id.equals(next.id()) || next.version() != current.version() + 1
                    || !current.authorActorId().equals(next.authorActorId())) {
                throw new IllegalStateException("Policy transition violated aggregate identity");
            }
            Query cas = Query.query(Criteria.where("_id").is(id).and("version").is(current.version())
                    .and("status").is(current.status().name()));
            Update update = new Update().set("draft", next.draft()).set("draftSha256", next.draftSha256())
                    .set("status", next.status().name()).set("version", next.version())
                    .set("submittingActorId", next.submittingActorId()).set("submittedAt", next.submittedAt())
                    .set("reviewingActorId", next.reviewingActorId()).set("decidedAt", next.decidedAt())
                    .set("approvalEvidenceReference", next.approvalEvidenceReference())
                    .set("publicationRequestId", next.publicationRequestId()).set("updatedAt", next.updatedAt());
            PolicyProposalDocument committed = mongo.findAndModify(cas, update,
                    FindAndModifyOptions.options().returnNew(true), PolicyProposalDocument.class);
            if (committed == null) {
                throw new PolicyGovernanceConflictException("Concurrent policy transition changed this proposal");
            }
            record(committed, current.status(), action, actor, operationId, commandSha256, next.updatedAt());
            if (next.status() == PolicyGovernanceState.PUBLICATION_PENDING
                    && current.status() == PolicyGovernanceState.APPROVED) {
                if (next.publicationRequestId() == null || next.approvalEvidenceReference() == null) {
                    throw new IllegalStateException("Publication intent lacks required identity or proof");
                }
                PolicyWebAuthnEvidence submission = mongo.findOne(Query.query(Criteria.where("proposalId")
                        .is(next.id()).and("action").is("POLICY_SUBMIT")), PolicyWebAuthnEvidence.class);
                if (submission == null || !next.authorActorId().equals(submission.actorId())
                        || !next.submittedAt().equals(submission.acceptedAt())
                        || !next.draftSha256().equals(submission.draftSha256())) {
                    throw new PolicyGovernanceConflictException("Verified author submission is required");
                }
                mongo.insert(new PolicyPublicationJobDocument(next.publicationRequestId(), next.id(), next.version(),
                        next.draft(), next.draftSha256(), next.authorActorId(),
                        "webauthn:" + submission.id(), next.submittedAt(),
                        next.reviewingActorId(), next.approvalEvidenceReference(), next.decidedAt(), actor,
                        next.updatedAt(), PolicyPublicationJobDocument.State.PENDING,
                        null, null, 0, 0, 0, null, null, null, 0, null));
            }
            return committed;
        });
    }

    Instant now() {
        // Mongo Date persists milliseconds. Hash the same canonical instant that survives persistence.
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void record(PolicyProposalDocument state, PolicyGovernanceState from, String action,
            String actor, String operationId, String commandSha256, Instant now) {
        var history = new PolicyGovernanceHistoryDocument(UUID.randomUUID().toString(), state.id(), state.version(),
                action, from, state.status(), actor, operationId, commandSha256, state.draftSha256(),
                state.approvalEvidenceReference(), now, null);
        mongo.insert(history);
        GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.POLICY_GOVERNANCE,
                history.id(), history, state.draft().governanceScope().institutionalScope(),
                actor, state.id(), operationId, commandSha256, state.id(), now);
        mongo.insert(new PolicyGovernanceOperationDocument(operationId, state.id(), commandSha256, actor, state, now));
    }

    private PolicyProposalDocument execute(String operationId, String digest, String actor,
            Supplier<PolicyProposalDocument> command) {
        PolicyProposalDocument replay = replay(operationId, digest, actor);
        if (replay != null) return replay;
        for (int attempt = 0; attempt < MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(ignored -> {
                    PolicyProposalDocument inTransactionReplay = replay(operationId, digest, actor);
                    return inTransactionReplay == null ? command.get() : inTransactionReplay;
                });
            } catch (RuntimeException failure) {
                PolicyProposalDocument committed = replay(operationId, digest, actor);
                if (committed != null) return committed;
                if (failure instanceof DuplicateKeyException) {
                    throw new PolicyGovernanceConflictException("Policy identity or operation already exists");
                }
                if (!transientTransactionFailure(failure) || attempt == MAX_TRANSIENT_ATTEMPTS - 1) {
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("Policy transaction attempts exhausted");
    }

    private PolicyProposalDocument replay(String operationId, String digest, String actor) {
        PolicyGovernanceOperationDocument prior = mongo.findById(operationId, PolicyGovernanceOperationDocument.class);
        if (prior == null) return null;
        if (!prior.commandSha256().equals(digest) || !prior.actorId().equals(actor)) {
            throw new PolicyGovernanceConflictException("Operation ID reused with conflicting intent or actor");
        }
        return prior.result();
    }

    private boolean transientTransactionFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MongoException mongoFailure
                    && mongoFailure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) return true;
        }
        return false;
    }
}
