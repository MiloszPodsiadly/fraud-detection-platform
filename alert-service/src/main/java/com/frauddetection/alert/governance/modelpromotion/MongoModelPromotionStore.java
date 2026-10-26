package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class MongoModelPromotionStore {

    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private static final int MAX_REVIEW_TRANSACTION_ATTEMPTS = 6;
    private static final int REVIEW_SETTLE_OBSERVATIONS = 10;
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final int MAX_PROPOSAL_LIMIT = 100;

    private final MongoTemplate mongoTemplate;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final ModelPromotionStateMachine stateMachine;
    private final ModelPromotionDecisionValidator decisionValidator;
    private final ModelPromotionLocalAuditFactory auditFactory;
    private final Clock clock;

    @Autowired
    public MongoModelPromotionStore(
            MongoTemplate mongoTemplate,
            RegulatedMutationTransactionRunner transactionRunner,
            ModelPromotionDecisionValidator decisionValidator,
            ModelPromotionLocalAuditFactory auditFactory
    ) {
        this(mongoTemplate, transactionRunner, decisionValidator, auditFactory, Clock.systemUTC());
    }

    MongoModelPromotionStore(
            MongoTemplate mongoTemplate,
            RegulatedMutationTransactionRunner transactionRunner,
            ModelPromotionDecisionValidator decisionValidator,
            ModelPromotionLocalAuditFactory auditFactory,
            Clock clock
    ) {
        this.mongoTemplate = mongoTemplate;
        this.transactionRunner = transactionRunner;
        this.stateMachine = new ModelPromotionStateMachine();
        this.decisionValidator = decisionValidator;
        this.auditFactory = auditFactory;
        this.clock = clock;
    }

    ModelPromotionMutationResult registerCandidate(ModelPromotionRegistrationCommand command) {
        requireTransactions();
        String aggregateKey = ModelPromotionPersistenceKey.aggregate(
                command.artifactIdentity(), command.governanceScope(), command.intendedUse()
        );
        String operationKey = ModelPromotionPersistenceKey.operation(
                aggregateKey, command.operationContext().operationId()
        );
        String commandSha256 = decisionValidator.registrationCommandSha256(command);
        return executeWithRetry(
                operationKey,
                commandSha256,
                command.operationContext(),
                () -> registerWithinTransaction(command, aggregateKey, operationKey, commandSha256)
        );
    }

    ModelPromotionProposalResult submitProposal(ModelPromotionProposalSubmission submission) {
        return submitProposal(submission, null);
    }

    ModelPromotionProposalResult submitProposal(
            ModelPromotionProposalSubmission submission,
            ModelPromotionProposalRequestIntent requestIntent
    ) {
        requireTransactions();
        ModelPromotionTransitionProposal transition = submission.transition();
        if (requestIntent != null && (!requestIntent.transition().equals(transition)
                || !requestIntent.proposalId().equals(submission.proposal().proposalId())
                || !requestIntent.actorId().equals(submission.operationContext().actorId())
                || !requestIntent.rationale().equals(submission.proposal().rationale()))) {
            throw new IllegalArgumentException("Proposal does not match the original request intent");
        }
        String aggregateKey = ModelPromotionPersistenceKey.aggregate(
                transition.artifactIdentity(), transition.governanceScope(), transition.intendedUse()
        );
        String commandSha256 = decisionValidator.proposalCommandSha256(submission);
        String requestIntentSha256 = requestIntent == null ? null
                : decisionValidator.proposalRequestIntentSha256(requestIntent);
        Optional<ModelPromotionProposalResult> replay = replayProposalSubmission(
                aggregateKey, transition.operationId(), commandSha256, requestIntentSha256,
                submission.operationContext()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        decisionValidator.validateProposal(submission);
        return executeProposalSubmissionWithRetry(
                aggregateKey,
                commandSha256,
                requestIntentSha256,
                submission,
                () -> submitProposalWithinTransaction(submission, aggregateKey, commandSha256, requestIntentSha256)
        );
    }

    Optional<ModelPromotionProposalResult> recoverSubmittedProposal(
            ModelPromotionProposalRequestIntent requestIntent,
            ModelPromotionOperationContext context
    ) {
        if (!requestIntent.actorId().equals(context.actorId())
                || !requestIntent.transition().operationId().equals(context.operationId())) {
            throw new IllegalArgumentException("Request intent and authenticated operation differ");
        }
        String aggregateKey = ModelPromotionPersistenceKey.aggregate(
                requestIntent.transition().artifactIdentity(),
                requestIntent.transition().governanceScope(),
                requestIntent.transition().intendedUse()
        );
        Optional<ModelPromotionProposalResult> recovered = replayProposalSubmission(
                aggregateKey,
                context.operationId(),
                null,
                decisionValidator.proposalRequestIntentSha256(requestIntent),
                context
        );
        if (recovered.isEmpty()) {
            assertOperationAvailable(aggregateKey, context.operationId());
        }
        return recovered;
    }

    Optional<ModelPromotionProposalDocument> findProposal(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId is required");
        }
        return Optional.ofNullable(mongoTemplate.findById(proposalId, ModelPromotionProposalDocument.class));
    }

    ModelPromotionProposalResult reviewProposal(
            ModelPromotionProposalReview review,
            Runnable preconditionRevalidation
    ) {
        return reviewProposal(review, preconditionRevalidation, null);
    }

    ModelPromotionProposalResult reviewProposal(
            ModelPromotionProposalReview review,
            Runnable preconditionRevalidation,
            Runnable signedProofInTransaction
    ) {
        return reviewProposal(review, preconditionRevalidation, signedProofInTransaction,
                signedProofInTransaction == null ? ReviewEvidence.NONE : ReviewEvidence.WEBAUTHN);
    }

    enum ReviewEvidence { NONE, WEBAUTHN, INDEPENDENT_EMERGENCY_GRANT }

    ModelPromotionProposalResult reviewProposal(
            ModelPromotionProposalReview review,
            Runnable preconditionRevalidation,
            Runnable protectedEvidenceInTransaction,
            ReviewEvidence evidenceKind
    ) {
        requireTransactions();
        Objects.requireNonNull(review, "review is required");
        Objects.requireNonNull(preconditionRevalidation, "preconditionRevalidation is required");
        if (evidenceKind == null || (evidenceKind == ReviewEvidence.NONE)
                != (protectedEvidenceInTransaction == null)) {
            throw new IllegalArgumentException("Review evidence kind and transaction callback differ");
        }
        String reviewCommandSha256 = decisionValidator.reviewCommandSha256(review);
        Optional<ModelPromotionProposalResult> replay = replayProposalReview(
                review.proposalId(), reviewCommandSha256, review.operationContext()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        return executeProposalReviewWithRetry(
                review,
                reviewCommandSha256,
                () -> reviewProposalWithinTransaction(review, reviewCommandSha256,
                        preconditionRevalidation, protectedEvidenceInTransaction, evidenceKind)
        );
    }

    Optional<ModelPromotionAggregate> currentState(
            com.frauddetection.alert.governance.ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse
    ) {
        String key = ModelPromotionPersistenceKey.aggregate(artifactIdentity, governanceScope, intendedUse);
        return Optional.ofNullable(mongoTemplate.findById(key, ModelPromotionStateDocument.class))
                .map(ModelPromotionStateDocument::toAggregate);
    }

    List<ModelPromotionDecisionDocument> history(
            com.frauddetection.alert.governance.ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            int limit
    ) {
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException("history limit must be between 1 and " + MAX_HISTORY_LIMIT);
        }
        String key = ModelPromotionPersistenceKey.aggregate(artifactIdentity, governanceScope, intendedUse);
        Query query = Query.query(Criteria.where("aggregate_key").is(key))
                .with(Sort.by(Sort.Direction.DESC, "committed_at")
                        .and(Sort.by(Sort.Direction.ASC, "operation_id")))
                .limit(limit);
        return List.copyOf(mongoTemplate.find(query, ModelPromotionDecisionDocument.class));
    }

    List<ModelPromotionProposalDocument> proposals(
            com.frauddetection.alert.governance.ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            int limit
    ) {
        if (limit < 1 || limit > MAX_PROPOSAL_LIMIT) {
            throw new IllegalArgumentException("proposal limit must be between 1 and " + MAX_PROPOSAL_LIMIT);
        }
        String key = ModelPromotionPersistenceKey.aggregate(artifactIdentity, governanceScope, intendedUse);
        Query query = Query.query(Criteria.where("aggregate_key").is(key))
                .with(Sort.by(Sort.Direction.DESC, "submitted_at")
                        .and(Sort.by(Sort.Direction.ASC, "_id")))
                .limit(limit);
        return List.copyOf(mongoTemplate.find(query, ModelPromotionProposalDocument.class));
    }

    private ModelPromotionProposalResult submitProposalWithinTransaction(
            ModelPromotionProposalSubmission submission,
            String aggregateKey,
            String commandSha256,
            String requestIntentSha256
    ) {
        Optional<ModelPromotionProposalResult> replay = replayProposalSubmission(
                aggregateKey, submission.transition().operationId(), commandSha256, requestIntentSha256,
                submission.operationContext()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        ModelPromotionStateDocument persisted = mongoTemplate.findById(
                aggregateKey, ModelPromotionStateDocument.class
        );
        if (persisted == null) {
            throw new ModelPromotionPersistenceConflictException("Model promotion candidate is not registered");
        }
        stateMachine.validateProposal(persisted.toAggregate(), submission.transition());
        claimOperation(
                aggregateKey,
                submission.transition().operationId(),
                ModelPromotionOperationClaimDocument.OperationType.SUBMIT_PROPOSAL
        );
        Instant submittedAt = clock.instant();
        ModelPromotionProposalDocument proposal = ModelPromotionProposalDocument.pending(
                aggregateKey,
                commandSha256,
                requestIntentSha256,
                submission,
                auditFactory.accepted(
                        "SUBMIT_MODEL_PROMOTION_PROPOSAL",
                        submission.operationContext(),
                        submittedAt
                ),
                submittedAt
        );
        mongoTemplate.insert(proposal);
        return proposal.toResult(false);
    }

    private ModelPromotionProposalResult reviewProposalWithinTransaction(
            ModelPromotionProposalReview review,
            String reviewCommandSha256,
            Runnable preconditionRevalidation,
            Runnable protectedEvidenceInTransaction,
            ReviewEvidence evidenceKind
    ) {
        ModelPromotionProposalDocument proposal = mongoTemplate.findById(
                review.proposalId(), ModelPromotionProposalDocument.class
        );
        if (proposal == null) {
            throw new ModelPromotionPersistenceConflictException("Model promotion proposal is unavailable");
        }
        Optional<ModelPromotionProposalResult> replay = replayProposalReview(
                proposal, reviewCommandSha256, review.operationContext()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        if (proposal.reviewStatus() != ModelPromotionProposalDocument.ReviewStatus.PENDING) {
            throw new ModelPromotionPersistenceConflictException("Model promotion proposal was already reviewed");
        }
        if (proposal.proposerIdentity() == null) {
            throw new ModelPromotionPersistenceConflictException(
                    "Legacy pending proposal has no issuer binding; resubmit with new proposal and operation IDs");
        }
        if (proposal.proposal().proposedBy().equals(review.operationContext().actorId())) {
            throw new ModelPromotionPersistenceConflictException(
                    "A model promotion proposer cannot review their own proposal"
            );
        }
        UpdateResult claimed = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(review.proposalId())
                        .and("review_status").is(ModelPromotionProposalDocument.ReviewStatus.PENDING.name())),
                new Update().set("review_status", ModelPromotionProposalDocument.ReviewStatus.REVIEW_IN_PROGRESS.name()),
                ModelPromotionProposalDocument.class
        );
        if (claimed.getModifiedCount() != 1) {
            throw new ModelPromotionPersistenceConflictException("Model promotion proposal review is already in progress");
        }
        claimOperation(
                proposal.aggregateKey(),
                review.operationContext().operationId(),
                ModelPromotionOperationClaimDocument.OperationType.REVIEW_PROPOSAL
        );
        preconditionRevalidation.run();
        if (protectedEvidenceInTransaction != null) protectedEvidenceInTransaction.run();

        Instant reviewedAt = clock.instant();
        ModelPromotionProposalReview committedReview = protectedEvidenceInTransaction == null
                ? review : acceptedReview(review, reviewedAt);
        if (committedReview.approval().outcome() == ModelPromotionApproval.Outcome.APPROVED) {
            DurableModelPromotionTransitionCommand transition = approvedTransition(proposal, committedReview);
            decisionValidator.validate(transition);
            String operationKey = ModelPromotionPersistenceKey.operation(
                    proposal.aggregateKey(), review.operationContext().operationId()
            );
            transitionWithinTransaction(
                    transition,
                    proposal.aggregateKey(),
                    operationKey,
                    decisionValidator.transitionCommandSha256(transition)
            );
            return completeProposalReview(
                    proposal,
                    committedReview,
                    reviewCommandSha256,
                    ModelPromotionProposalDocument.ReviewStatus.APPROVED,
                    evidenceKind == ReviewEvidence.INDEPENDENT_EMERGENCY_GRANT
                            ? "EMERGENCY_RETIRE_MODEL_PROMOTION_PROPOSAL"
                            : "APPROVE_MODEL_PROMOTION_PROPOSAL",
                    reviewedAt
            );
        }
        recordProposalRejection(proposal, committedReview, reviewCommandSha256, reviewedAt);
        return completeProposalReview(
                proposal,
                committedReview,
                reviewCommandSha256,
                ModelPromotionProposalDocument.ReviewStatus.REJECTED,
                "REJECT_MODEL_PROMOTION_PROPOSAL",
                reviewedAt
        );
    }

    private ModelPromotionProposalReview acceptedReview(ModelPromotionProposalReview review, Instant acceptedAt) {
        var old = review.approval();
        var context = review.operationContext();
        return new ModelPromotionProposalReview(review.proposalId(), new ModelPromotionApproval(
                old.approvalId(), old.proposalId(), old.outcome(), old.approvedBy(), acceptedAt,
                old.rationale()), new ModelPromotionOperationContext(context.operationId(), context.actorId(),
                context.authorizationFingerprint(), acceptedAt, context.actorIdentity()));
    }

    private void recordProposalRejection(
            ModelPromotionProposalDocument proposal,
            ModelPromotionProposalReview review,
            String reviewCommandSha256,
            Instant reviewedAt
    ) {
        ModelPromotionStateDocument persisted = mongoTemplate.findById(
                proposal.aggregateKey(), ModelPromotionStateDocument.class
        );
        if (persisted == null) {
            throw new ModelPromotionPersistenceConflictException("Model promotion candidate is not registered");
        }
        ModelPromotionAggregate current = persisted.toAggregate();
        String operationKey = ModelPromotionPersistenceKey.operation(
                proposal.aggregateKey(), review.operationContext().operationId()
        );
        ModelPromotionDecisionDocument decision = new ModelPromotionDecisionDocument(
                operationKey,
                proposal.aggregateKey(),
                review.operationContext().operationId(),
                reviewCommandSha256,
                review.operationContext().actorId(),
                review.operationContext().authorizationFingerprint(),
                ModelPromotionDecisionDocument.EventType.TRANSITION_PROPOSAL_REJECTED,
                current.artifactIdentity(),
                current.governanceScope(),
                current.intendedUse(),
                current.lifecycleState(),
                current.lifecycleState(),
                current.revision(),
                null,
                proposal.policySnapshot(),
                proposal.assessmentSnapshot(),
                proposal.proposal(),
                review.approval(),
                auditFactory.accepted(
                        "REJECT_MODEL_PROMOTION_PROPOSAL",
                        review.operationContext(),
                        reviewedAt
                ),
                reviewedAt,
                review.operationContext().actorIdentity()
        );
        mongoTemplate.insert(decision);
    }

    private DurableModelPromotionTransitionCommand approvedTransition(
            ModelPromotionProposalDocument proposal,
            ModelPromotionProposalReview review
    ) {
        ModelPromotionTransitionProposal submitted = proposal.transition();
        ModelPromotionAssessment assessment = proposal.assessmentSnapshot();
        ModelPromotionDecisionReference reference = new ModelPromotionDecisionReference(
                review.approval().approvalId(),
                new ModelPromotionDecisionReference.ProposalReference(proposal.proposalId()),
                assessment == null ? null : new ModelPromotionDecisionReference.EvidenceBundleReference(
                        assessment.content().evidence().evidenceBundleSha256(),
                        assessment.content().evidence().evidenceBundleSha256()
                ),
                assessment == null ? null : new ModelPromotionDecisionReference.AssessmentReference(
                        assessment.canonicalSha256(),
                        assessment.canonicalSha256()
                ),
                new ModelPromotionDecisionReference.ApprovalReference(review.approval().approvalId())
        );
        return new DurableModelPromotionTransitionCommand(
                new ModelPromotionTransitionCommand(
                        submitted.artifactIdentity(),
                        submitted.governanceScope(),
                        submitted.intendedUse(),
                        submitted.sourceState(),
                        submitted.expectedRevision(),
                        submitted.requestedTargetState(),
                        review.operationContext().operationId(),
                        reference
                ),
                review.operationContext(),
                proposal.policySnapshot(),
                assessment,
                proposal.proposal(),
                review.approval()
        );
    }

    private ModelPromotionProposalResult completeProposalReview(
            ModelPromotionProposalDocument proposal,
            ModelPromotionProposalReview review,
            String reviewCommandSha256,
            ModelPromotionProposalDocument.ReviewStatus reviewStatus,
            String auditAction,
            Instant reviewedAt
    ) {
        ModelPromotionDecisionDocument.LocalAuditEvidence reviewAudit = auditFactory.accepted(
                auditAction, review.operationContext(), reviewedAt
        );
        UpdateResult completed = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(proposal.proposalId())
                        .and("review_status").is(ModelPromotionProposalDocument.ReviewStatus.REVIEW_IN_PROGRESS.name())),
                new Update()
                        .set("review_status", reviewStatus.name())
                        .set("approval", review.approval())
                        .set("review_operation_id", review.operationContext().operationId())
                        .set("review_command_sha256", reviewCommandSha256)
                        .set("reviewer_authorization_fingerprint", review.operationContext().authorizationFingerprint())
                        .set("reviewer_identity", review.operationContext().actorIdentity())
                        .set("review_audit", reviewAudit)
                        .set("reviewed_at", reviewedAt),
                ModelPromotionProposalDocument.class
        );
        if (completed.getModifiedCount() != 1) {
            throw new ModelPromotionPersistenceConflictException("Model promotion proposal review could not be finalized");
        }
        return new ModelPromotionProposalDocument(
                proposal.proposalId(),
                proposal.aggregateKey(),
                proposal.operationId(),
                proposal.commandSha256(),
                proposal.requestIntentSha256(),
                proposal.actorId(),
                proposal.authorizationFingerprint(),
                proposal.transition(),
                proposal.policySnapshot(),
                proposal.assessmentSnapshot(),
                proposal.proposal(),
                reviewStatus,
                review.approval(),
                review.operationContext().operationId(),
                reviewCommandSha256,
                review.operationContext().authorizationFingerprint(),
                proposal.submissionAudit(),
                reviewAudit,
                proposal.submittedAt(),
                reviewedAt,
                proposal.proposerIdentity(),
                review.operationContext().actorIdentity()
        ).toResult(false);
    }

    private ModelPromotionMutationResult registerWithinTransaction(
            ModelPromotionRegistrationCommand command,
            String aggregateKey,
            String operationKey,
            String commandSha256
    ) {
        Optional<ModelPromotionMutationResult> replay = replay(
                operationKey, commandSha256, command.operationContext()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        claimOperation(
                aggregateKey,
                command.operationContext().operationId(),
                ModelPromotionOperationClaimDocument.OperationType.REGISTER_CANDIDATE
        );
        Instant committedAt = clock.instant();
        ModelPromotionStateDocument state = ModelPromotionStateDocument.candidate(command, committedAt);
        mongoTemplate.insert(state);
        ModelPromotionDecisionDocument.LocalAuditEvidence audit = auditFactory.accepted(
                "REGISTER_MODEL_PROMOTION_CANDIDATE", command.operationContext(), committedAt
        );
        ModelPromotionDecisionDocument decision = new ModelPromotionDecisionDocument(
                operationKey,
                aggregateKey,
                command.operationContext().operationId(),
                commandSha256,
                command.operationContext().actorId(),
                command.operationContext().authorizationFingerprint(),
                ModelPromotionDecisionDocument.EventType.CANDIDATE_REGISTERED,
                command.artifactIdentity(),
                command.governanceScope(),
                command.intendedUse(),
                null,
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                null,
                null,
                null,
                null,
                null,
                audit,
                committedAt,
                command.operationContext().actorIdentity()
        );
        mongoTemplate.insert(decision);
        return decision.toResult();
    }

    private ModelPromotionMutationResult transitionWithinTransaction(
            DurableModelPromotionTransitionCommand request,
            String aggregateKey,
            String operationKey,
            String commandSha256
    ) {
        Optional<ModelPromotionMutationResult> replay = replay(
                operationKey, commandSha256, request.operationContext()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        ModelPromotionStateDocument persisted = mongoTemplate.findById(
                aggregateKey, ModelPromotionStateDocument.class
        );
        if (persisted == null) {
            throw new ModelPromotionPersistenceConflictException("Model promotion candidate is not registered");
        }
        ModelPromotionAggregate current = persisted.toAggregate();
        ModelPromotionAggregate next = stateMachine.transition(current, request.transition());
        Instant committedAt = clock.instant();

        if (next.lifecycleState() == ModelPromotionLifecycleState.CHAMPION) {
            claimChampion(aggregateKey, next, committedAt);
        }

        UpdateResult update = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(aggregateKey)
                        .and("revision").is(current.revision())
                        .and("lifecycle_state").is(current.lifecycleState().name())),
                new Update()
                        .set("lifecycle_state", next.lifecycleState().name())
                        .set("revision", next.revision())
                        .set("accepted_decision_id", next.acceptedDecisionId())
                        .set("updated_at", committedAt),
                ModelPromotionStateDocument.class
        );
        if (update.getModifiedCount() != 1) {
            throw new ModelPromotionPersistenceConflictException(
                    "Model promotion state changed before the transition could commit"
            );
        }

        if (current.lifecycleState() == ModelPromotionLifecycleState.CHAMPION
                && next.lifecycleState() == ModelPromotionLifecycleState.RETIRED) {
            releaseChampion(aggregateKey, current);
        }

        ModelPromotionDecisionDocument.LocalAuditEvidence audit = auditFactory.accepted(
                "ACCEPT_MODEL_PROMOTION_TRANSITION", request.operationContext(), committedAt
        );
        ModelPromotionDecisionDocument decision = new ModelPromotionDecisionDocument(
                operationKey,
                aggregateKey,
                request.operationContext().operationId(),
                commandSha256,
                request.operationContext().actorId(),
                request.operationContext().authorizationFingerprint(),
                ModelPromotionDecisionDocument.EventType.LIFECYCLE_TRANSITION_ACCEPTED,
                next.artifactIdentity(),
                next.governanceScope(),
                next.intendedUse(),
                current.lifecycleState(),
                next.lifecycleState(),
                next.revision(),
                next.acceptedDecisionId(),
                request.policySnapshot(),
                request.assessmentSnapshot(),
                request.proposal(),
                request.approval(),
                audit,
                committedAt,
                request.operationContext().actorIdentity()
        );
        mongoTemplate.insert(decision);
        return decision.toResult();
    }

    private void claimChampion(String aggregateKey, ModelPromotionAggregate next, Instant committedAt) {
        ModelGovernanceScope scope = next.governanceScope();
        mongoTemplate.insert(new ModelPromotionChampionClaimDocument(
                ModelPromotionPersistenceKey.champion(scope, next.intendedUse()),
                aggregateKey,
                scope.institutionalScope(),
                scope.environment(),
                next.intendedUse(),
                next.acceptedDecisionId(),
                committedAt
        ));
    }

    private void releaseChampion(String aggregateKey, ModelPromotionAggregate current) {
        String championKey = ModelPromotionPersistenceKey.champion(
                current.governanceScope(), current.intendedUse()
        );
        DeleteResult deleted = mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(championKey).and("aggregate_key").is(aggregateKey)),
                ModelPromotionChampionClaimDocument.class
        );
        if (deleted.getDeletedCount() != 1) {
            throw new IllegalStateException("Authoritative CHAMPION claim is missing or corrupted");
        }
    }

    private ModelPromotionProposalResult executeProposalSubmissionWithRetry(
            String aggregateKey,
            String commandSha256,
            String requestIntentSha256,
            ModelPromotionProposalSubmission submission,
            Supplier<ModelPromotionProposalResult> transaction
    ) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            Optional<ModelPromotionProposalResult> replay = replayProposalSubmission(
                    aggregateKey,
                    submission.transition().operationId(),
                    commandSha256,
                    requestIntentSha256,
                    submission.operationContext()
            );
            if (replay.isPresent()) {
                return replay.get();
            }
            try {
                return transactionRunner.runLocalCommit(transaction);
            } catch (DuplicateKeyException exception) {
                Optional<ModelPromotionProposalResult> committed = replayProposalSubmission(
                        aggregateKey,
                        submission.transition().operationId(),
                        commandSha256,
                        requestIntentSha256,
                        submission.operationContext()
                );
                if (committed.isPresent()) {
                    return committed.get();
                }
                throw new ModelPromotionPersistenceConflictException(
                        "Model promotion proposal identity or operation already exists", exception
                );
            } catch (RuntimeException exception) {
                Optional<ModelPromotionProposalResult> committed = replayProposalSubmission(
                        aggregateKey,
                        submission.transition().operationId(),
                        commandSha256,
                        requestIntentSha256,
                        submission.operationContext()
                );
                if (committed.isPresent()) {
                    return committed.get();
                }
                if (!isTransientTransactionFailure(exception) || attempt == MAX_TRANSACTION_ATTEMPTS) {
                    throw exception;
                }
                last = exception;
            }
        }
        throw last == null ? new IllegalStateException("Model promotion proposal transaction failed") : last;
    }

    private ModelPromotionProposalResult executeProposalReviewWithRetry(
            ModelPromotionProposalReview review,
            String reviewCommandSha256,
            Supplier<ModelPromotionProposalResult> transaction
    ) {
        RuntimeException lastTransient = null;
        for (int attempt = 1; attempt <= MAX_REVIEW_TRANSACTION_ATTEMPTS; attempt++) {
            Optional<ModelPromotionProposalResult> settled = inspectDurableReviewOutcome(review, reviewCommandSha256);
            if (settled.isPresent()) return settled.get();
            try {
                return transactionRunner.runLocalCommit(transaction);
            } catch (DuplicateKeyException exception) {
                Optional<ModelPromotionProposalResult> committed = inspectDurableReviewOutcome(
                        review, reviewCommandSha256);
                if (committed.isPresent()) return committed.get();
                throw new ModelPromotionPersistenceConflictException(
                        "Model promotion review conflicts with an existing decision or champion claim", exception
                );
            } catch (RuntimeException exception) {
                if (hasTransactionLabel(exception, MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    return awaitDurableReviewOutcome(review, reviewCommandSha256, exception,
                            "Model promotion review commit outcome is unknown");
                }
                Optional<ModelPromotionProposalResult> committed = inspectDurableReviewOutcome(
                        review, reviewCommandSha256);
                if (committed.isPresent()) return committed.get();
                if (!isTransientTransactionFailure(exception)) {
                    throw exception;
                }
                lastTransient = exception;
                if (attempt < MAX_REVIEW_TRANSACTION_ATTEMPTS) {
                    reviewRetryPause(20L << (attempt - 1), exception);
                }
            }
        }
        return awaitDurableReviewOutcome(review, reviewCommandSha256, lastTransient,
                "Model promotion review remained unavailable after bounded transaction retries");
    }

    private Optional<ModelPromotionProposalResult> inspectDurableReviewOutcome(
            ModelPromotionProposalReview review, String reviewCommandSha256) {
        ModelPromotionProposalDocument durable = mongoTemplate.findById(
                review.proposalId(), ModelPromotionProposalDocument.class);
        if (durable == null) {
            throw new ModelPromotionPersistenceConflictException("Model promotion proposal is unavailable");
        }
        Optional<ModelPromotionProposalResult> replay = replayProposalReview(
                durable, reviewCommandSha256, review.operationContext());
        if (replay.isPresent()) return replay;
        if (durable.reviewStatus() != ModelPromotionProposalDocument.ReviewStatus.PENDING) {
            throw new ModelPromotionPersistenceConflictException("Model promotion proposal was already reviewed");
        }
        return Optional.empty();
    }

    private ModelPromotionProposalResult awaitDurableReviewOutcome(
            ModelPromotionProposalReview review, String reviewCommandSha256,
            RuntimeException cause, String unavailableMessage) {
        for (int observation = 0; observation < REVIEW_SETTLE_OBSERVATIONS; observation++) {
            reviewRetryPause(100, cause);
            Optional<ModelPromotionProposalResult> settled = inspectDurableReviewOutcome(review, reviewCommandSha256);
            if (settled.isPresent()) return settled.get();
        }
        throw new DataAccessResourceFailureException(unavailableMessage, cause);
    }

    private void reviewRetryPause(long millis, RuntimeException cause) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DataAccessResourceFailureException("Model promotion review retry was interrupted", cause);
        }
    }

    private Optional<ModelPromotionProposalResult> replayProposalSubmission(
            String aggregateKey,
            String operationId,
            String commandSha256,
            String requestIntentSha256,
            ModelPromotionOperationContext context
    ) {
        ModelPromotionProposalDocument existing = mongoTemplate.findOne(
                Query.query(Criteria.where("aggregate_key").is(aggregateKey)
                        .and("operation_id").is(operationId)),
                ModelPromotionProposalDocument.class
        );
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.actorId().equals(context.actorId())
                || !existing.authorizationFingerprint().equals(context.authorizationFingerprint())
                || !sameActorIdentity(existing.proposerIdentity(), context.actorIdentity())) {
            throw new ModelPromotionPersistenceConflictException("Idempotency operation is unavailable to this caller");
        }
        boolean sameIntent = requestIntentSha256 == null
                ? existing.commandSha256().equals(commandSha256)
                : requestIntentSha256.equals(existing.requestIntentSha256());
        if (!sameIntent) {
            throw new ModelPromotionPersistenceConflictException(
                    "Idempotency operation was already used for different canonical content"
            );
        }
        return Optional.of(existing.toResult(true));
    }

    private Optional<ModelPromotionProposalResult> replayProposalReview(
            String proposalId,
            String reviewCommandSha256,
            ModelPromotionOperationContext context
    ) {
        ModelPromotionProposalDocument existing = mongoTemplate.findById(
                proposalId, ModelPromotionProposalDocument.class
        );
        if (existing == null) {
            return Optional.empty();
        }
        return replayProposalReview(existing, reviewCommandSha256, context);
    }

    private Optional<ModelPromotionProposalResult> replayProposalReview(
            ModelPromotionProposalDocument existing,
            String reviewCommandSha256,
            ModelPromotionOperationContext context
    ) {
        if (existing.reviewOperationId() == null || !existing.reviewOperationId().equals(context.operationId())) {
            return Optional.empty();
        }
        if (existing.approval() == null || !existing.approval().approvedBy().equals(context.actorId())
                || !existing.reviewerAuthorizationFingerprint().equals(context.authorizationFingerprint())
                || !sameActorIdentity(existing.reviewerIdentity(), context.actorIdentity())) {
            throw new ModelPromotionPersistenceConflictException("Idempotency operation is unavailable to this caller");
        }
        if (!existing.reviewCommandSha256().equals(reviewCommandSha256)) {
            throw new ModelPromotionPersistenceConflictException(
                    "Idempotency operation was already used for different canonical content"
            );
        }
        return Optional.of(existing.toResult(true));
    }

    private ModelPromotionMutationResult executeWithRetry(
            String operationKey,
            String commandSha256,
            ModelPromotionOperationContext context,
            Supplier<ModelPromotionMutationResult> transaction
    ) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            Optional<ModelPromotionMutationResult> replay = replay(operationKey, commandSha256, context);
            if (replay.isPresent()) {
                return replay.get();
            }
            try {
                return transactionRunner.runLocalCommit(transaction);
            } catch (DuplicateKeyException exception) {
                Optional<ModelPromotionMutationResult> committed = replay(operationKey, commandSha256, context);
                if (committed.isPresent()) {
                    return committed.get();
                }
                throw new ModelPromotionPersistenceConflictException(
                        "Model promotion identity or decision already exists", exception
                );
            } catch (RuntimeException exception) {
                Optional<ModelPromotionMutationResult> committed = replay(operationKey, commandSha256, context);
                if (committed.isPresent()) {
                    return committed.get();
                }
                if (!isTransientTransactionFailure(exception) || attempt == MAX_TRANSACTION_ATTEMPTS) {
                    throw exception;
                }
                last = exception;
            }
        }
        throw last == null ? new IllegalStateException("Model promotion transaction failed") : last;
    }

    private Optional<ModelPromotionMutationResult> replay(
            String operationKey,
            String commandSha256,
            ModelPromotionOperationContext context
    ) {
        ModelPromotionDecisionDocument existing = mongoTemplate.findById(
                operationKey, ModelPromotionDecisionDocument.class
        );
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.actorId().equals(context.actorId())
                || !existing.authorizationFingerprint().equals(context.authorizationFingerprint())
                || !sameActorIdentity(existing.actorIdentity(), context.actorIdentity())) {
            throw new ModelPromotionPersistenceConflictException("Idempotency operation is unavailable to this caller");
        }
        if (!existing.commandSha256().equals(commandSha256)) {
            throw new ModelPromotionPersistenceConflictException(
                    "Idempotency operation was already used for different canonical content"
            );
        }
        return Optional.of(existing.toResult().asReplay());
    }

    private boolean sameActorIdentity(ModelPromotionActorIdentity persisted,
            ModelPromotionActorIdentity current) {
        return persisted == null ? current == null : persisted.equals(current);
    }

    private void claimOperation(
            String aggregateKey,
            String operationId,
            ModelPromotionOperationClaimDocument.OperationType operationType
    ) {
        assertOperationAvailable(aggregateKey, operationId);
        mongoTemplate.insert(new ModelPromotionOperationClaimDocument(
                ModelPromotionPersistenceKey.operation(aggregateKey, operationId),
                aggregateKey,
                operationId,
                operationType
        ));
    }

    private void assertOperationAvailable(String aggregateKey, String operationId) {
        String operationKey = ModelPromotionPersistenceKey.operation(aggregateKey, operationId);
        Query priorOperation = Query.query(Criteria.where("aggregate_key").is(aggregateKey)
                .and("operation_id").is(operationId));
        if (mongoTemplate.exists(Query.query(Criteria.where("_id").is(operationKey)),
                ModelPromotionOperationClaimDocument.class)
                || mongoTemplate.exists(priorOperation, ModelPromotionDecisionDocument.class)
                || mongoTemplate.exists(priorOperation, ModelPromotionProposalDocument.class)) {
            throw new ModelPromotionPersistenceConflictException(
                    "Model promotion operation ID is already used by this aggregate"
            );
        }
    }

    private boolean isTransientTransactionFailure(Throwable throwable) {
        return hasTransactionLabel(throwable, MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
    }

    private boolean hasTransactionLabel(Throwable throwable, String label) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(label)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requireTransactions() {
        if (transactionRunner.mode() != RegulatedMutationTransactionMode.REQUIRED) {
            throw new IllegalStateException("Model promotion persistence requires Mongo transactions");
        }
    }
}
