package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;

/** The per-scope fence and policy insert participate in one Mongo replica-set transaction. */
final class MongoModelPromotionPolicyPublicationStore {

    private static final String FENCE_COLLECTION = "model_promotion_policy_publication_fences";
    private static final int MAX_PUBLICATION_ATTEMPTS = 3;

    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactions;

    MongoModelPromotionPolicyPublicationStore(MongoTemplate mongoTemplate, TransactionTemplate transactions) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);
        this.transactions = Objects.requireNonNull(transactions);
    }

    ModelPromotionPolicyPublicationReceipt publish(ModelPromotionPolicy policy, Instant publicationTime,
            InternalPolicyApprovalArtifact approvalProof,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(publicationTime);
        Objects.requireNonNull(approvalProof);
        Objects.requireNonNull(authorization);
        ensureIndexes();
        for (int attempt = 1; attempt <= MAX_PUBLICATION_ATTEMPTS; attempt++) {
            try {
                ModelPromotionPolicyPublicationReceipt receipt = transactions.execute(status -> publishWithinTransaction(
                        policy, publicationTime, approvalProof, authorization));
                return Objects.requireNonNull(receipt, "Mongo publication transaction returned no receipt");
            } catch (PublicationConflictException exception) {
                throw exception;
            } catch (DuplicateKeyException exception) {
                if (attempt == MAX_PUBLICATION_ATTEMPTS) {
                    throw new PublicationConflictException("Policy revision or concurrent publication conflicts", exception);
                }
            } catch (RuntimeException exception) {
                if (retryableTransactionFailure(exception) && attempt < MAX_PUBLICATION_ATTEMPTS) {
                    continue;
                }
                if (exception instanceof DataAccessException || exception instanceof MongoException
                        || retryableTransactionFailure(exception)) {
                    throw new IllegalStateException("Mongo policy publication did not commit", exception);
                }
                throw exception;
            }
        }
        throw new IllegalStateException("Mongo policy publication exhausted transaction retries");
    }

    private boolean retryableTransactionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo && (mongo.hasErrorLabel(
                    MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) || mongo.hasErrorLabel(
                    MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))) {
                return true;
            }
        }
        return false;
    }

    private ModelPromotionPolicyPublicationReceipt publishWithinTransaction(
            ModelPromotionPolicy policy, Instant publicationTime, InternalPolicyApprovalArtifact approvalProof,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization) {
        ModelPromotionPolicyDocument proposed = ModelPromotionPolicyDocument.from(policy, publicationTime);
        InternalPolicyApprovalEvidenceDocument evidence = evidence(proposed, approvalProof, authorization,
                publicationTime);
        ModelPromotionPolicy.Content content = policy.content();
        ModelGovernanceScope scope = content.governanceScope();
        String fenceId = String.join(":", scope.institutionalScope(), scope.environment().name(),
                content.intendedUse().name());
        mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(fenceId)),
                new Update().inc("generation", 1), FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class, FENCE_COLLECTION);

        ModelPromotionPolicyDocument existing = mongoTemplate.findById(
                proposed.policyKey(), ModelPromotionPolicyDocument.class);
        if (existing != null) {
            if (!existing.policy().content().equals(policy.content())
                    || !existing.policy().canonicalSha256().equals(policy.canonicalSha256())) {
                throw new PublicationConflictException("An immutable policy revision already has different content");
            }
            InternalPolicyApprovalEvidenceDocument saved = mongoTemplate.findById(proposed.policyKey(),
                    InternalPolicyApprovalEvidenceDocument.class);
            if (saved == null || !sameEvidence(evidence, saved)
                    || !saved.policySignature().equals(existing.policy().authorizationEvidence().signature())
                    || !saved.publishedAt().equals(existing.publishedAt())
                    || !saved.approvalProofSha256().equals(
                        InternalPolicyApprovalArtifact.proofSha256(new ObjectMapper(), saved.approvalProof()))) {
                throw new PublicationConflictException("Policy approval evidence is missing or differs");
            }
            return receipt(policy, existing.publishedAt() == null ? publicationTime : existing.publishedAt(), true);
        }
        Query overlap = Query.query(Criteria.where("institutional_scope").is(scope.institutionalScope())
                .and("environment").is(scope.environment())
                .and("intended_use").is(content.intendedUse())
                .and("effective_from").lt(proposed.effectiveTo())
                .and("effective_to").gt(proposed.effectiveFrom()));
        if (mongoTemplate.exists(overlap, ModelPromotionPolicyDocument.class)) {
            throw new PublicationConflictException("Policy effective period overlaps a published policy");
        }
        mongoTemplate.insert(proposed);
        mongoTemplate.insert(evidence);
        return receipt(policy, publicationTime, false);
    }

    private InternalPolicyApprovalEvidenceDocument evidence(ModelPromotionPolicyDocument policy,
            InternalPolicyApprovalArtifact proof,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization, Instant publishedAt) {
        ModelPromotionPolicy signed = policy.policy();
        InternalPolicyApprovalArtifact.AuthorStatement author = proof.authorStatement();
        InternalPolicyApprovalArtifact.ApprovalDecision decision = proof.approvalDecision();
        if (!author.policyCanonicalSha256().equals(signed.canonicalSha256())
                || !author.policyId().equals(signed.content().policyId())
                || author.revision() != signed.content().revision()
                || !author.issuerIdentity().equals(signed.authorizationEvidence().authorityIdentity())
                || !author.issuerKeyId().equals(signed.authorizationEvidence().keyId())
                || !authorization.canonicalSha256().equals(signed.canonicalSha256())
                || !authorization.approverIdentity().equals(decision.approverIdentity())
                || !authorization.publisherIdentity().equals(author.publisherIdentity())
                || !authorization.issuerIdentity().equals(author.issuerIdentity())
                || !authorization.keyId().equals(author.issuerKeyId())
                || !authorization.governanceScope().equals(signed.content().governanceScope())
                || authorization.intendedUse() != signed.content().intendedUse()) {
            throw new PublicationConflictException("Approval proof does not identify the signed policy");
        }
        return new InternalPolicyApprovalEvidenceDocument(policy.policyKey(), signed.canonicalSha256(),
                InternalPolicyApprovalArtifact.proofSha256(new ObjectMapper(), proof), proof,
                decision.approverIdentity(), decision.approverKeyId(), authorization.approverPublicKeySha256(),
                author.publisherIdentity(), author.publisherKeyId(), authorization.publisherPublicKeySha256(),
                authorization.approvalTrustGeneration(), authorization.approvalTrustSha256(),
                InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER, authorization.governanceScope(),
                authorization.intendedUse(), author.issuerIdentity(), author.issuerKeyId(),
                authorization.issuerPublicKeySha256(),
                signed.authorizationEvidence().signature(), publishedAt);
    }

    private boolean sameEvidence(InternalPolicyApprovalEvidenceDocument proposed,
            InternalPolicyApprovalEvidenceDocument saved) {
        return proposed.policyCanonicalSha256().equals(saved.policyCanonicalSha256())
                && proposed.approvalProofSha256().equals(saved.approvalProofSha256())
                && proposed.approvalProof().equals(saved.approvalProof())
                && proposed.approverIdentity().equals(saved.approverIdentity())
                && proposed.approverKeyId().equals(saved.approverKeyId())
                && proposed.approverPublicKeySha256().equals(saved.approverPublicKeySha256())
                && proposed.publisherIdentity().equals(saved.publisherIdentity())
                && proposed.publisherKeyId().equals(saved.publisherKeyId())
                && proposed.publisherPublicKeySha256().equals(saved.publisherPublicKeySha256())
                && proposed.approvalTrustGeneration() == saved.approvalTrustGeneration()
                && proposed.approvalTrustSha256().equals(saved.approvalTrustSha256())
                && proposed.publisherMandate() == saved.publisherMandate()
                && proposed.mandatedScope().equals(saved.mandatedScope())
                && proposed.mandatedUse() == saved.mandatedUse()
                && proposed.issuerIdentity().equals(saved.issuerIdentity())
                && proposed.issuerKeyId().equals(saved.issuerKeyId())
                && proposed.issuerPublicKeySha256().equals(saved.issuerPublicKeySha256());
    }

    private void ensureIndexes() {
        mongoTemplate.indexOps(ModelPromotionPolicyDocument.class).ensureIndex(new Index()
                .on("institutional_scope", Sort.Direction.ASC)
                .on("environment", Sort.Direction.ASC)
                .on("intended_use", Sort.Direction.ASC)
                .on("policy_id", Sort.Direction.ASC)
                .on("policy_revision", Sort.Direction.ASC)
                .unique().named(ModelPromotionPolicyDocument.REVISION_INDEX));
        mongoTemplate.indexOps(ModelPromotionPolicyDocument.class).ensureIndex(new Index()
                .on("institutional_scope", Sort.Direction.ASC)
                .on("environment", Sort.Direction.ASC)
                .on("intended_use", Sort.Direction.ASC)
                .on("effective_from", Sort.Direction.DESC)
                .on("effective_to", Sort.Direction.ASC)
                .named(ModelPromotionPolicyDocument.EFFECTIVE_LOOKUP_INDEX));
    }

    private ModelPromotionPolicyPublicationReceipt receipt(ModelPromotionPolicy policy, Instant publishedAt,
            boolean alreadyPublished) {
        return new ModelPromotionPolicyPublicationReceipt(policy.content().policyId(), policy.content().revision(),
                policy.canonicalSha256(), policy.authorizationEvidence().authorityIdentity(),
                policy.authorizationEvidence().keyId(), publishedAt, alreadyPublished);
    }

    static final class PublicationConflictException extends RuntimeException {
        PublicationConflictException(String message) {
            super(message);
        }

        PublicationConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
