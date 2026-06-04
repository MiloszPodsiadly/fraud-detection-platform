package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import com.frauddetection.alert.idempotency.IdempotencyCanonicalHasher;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class EngineIntelligenceFeedbackService {

    private static final int MAX_TRANSACTION_ID_LENGTH = 128;
    private static final int MAX_REASON_CODES = 5;
    private static final int MAX_ACCEPTED_STRING_LENGTH = 128;
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final Set<String> ACCURACY_ASSESSMENT_NAMES = Arrays.stream(EngineIntelligenceFeedbackAccuracyAssessment.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final List<String> FORBIDDEN_TERMS = List.of(
            "rawEvidence",
            "rawContribution",
            "featureSnapshot",
            "featureVector",
            "rawPayload",
            "payload",
            "endpoint",
            "token",
            "secret",
            "stacktrace",
            "exceptionMessage",
            "internalAggregation",
            "EngineIntelligenceProjection",
            "FraudEngineAggregationResult",
            "NormalizedFraud" + "EngineResult",
            "Scoring" + "Context",
            "rawMlResponse",
            "finalDecision",
            "recommendedAction",
            "approve",
            "decline",
            "block",
            "paymentAuthorization",
            "modelTrainingLabel",
            "ruleUpdate"
    );

    private final ScoredTransactionRepository scoredTransactionRepository;
    private final EngineIntelligenceFeedbackRepository feedbackRepository;
    private final SharedIdempotencyKeyPolicy idempotencyKeyPolicy;
    private final CurrentAnalystUser currentAnalystUser;
    private final AuditService auditService;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceFeedbackService(
            ScoredTransactionRepository scoredTransactionRepository,
            EngineIntelligenceFeedbackRepository feedbackRepository,
            SharedIdempotencyKeyPolicy idempotencyKeyPolicy,
            CurrentAnalystUser currentAnalystUser,
            AuditService auditService,
            RegulatedMutationTransactionRunner transactionRunner
    ) {
        this(
                scoredTransactionRepository,
                feedbackRepository,
                idempotencyKeyPolicy,
                currentAnalystUser,
                auditService,
                transactionRunner,
                Clock.systemUTC()
        );
    }

    EngineIntelligenceFeedbackService(
            ScoredTransactionRepository scoredTransactionRepository,
            EngineIntelligenceFeedbackRepository feedbackRepository,
            SharedIdempotencyKeyPolicy idempotencyKeyPolicy,
            CurrentAnalystUser currentAnalystUser,
            AuditService auditService,
            RegulatedMutationTransactionRunner transactionRunner,
            Clock clock
    ) {
        this.scoredTransactionRepository = Objects.requireNonNull(scoredTransactionRepository, "scoredTransactionRepository is required");
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository is required");
        this.idempotencyKeyPolicy = Objects.requireNonNull(idempotencyKeyPolicy, "idempotencyKeyPolicy is required");
        this.currentAnalystUser = Objects.requireNonNull(currentAnalystUser, "currentAnalystUser is required");
        this.auditService = Objects.requireNonNull(auditService, "auditService is required");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public EngineIntelligenceFeedbackResponse submit(
            String transactionId,
            EngineIntelligenceFeedbackRequest request,
            String idempotencyKey
    ) {
        String boundedTransactionId = normalizedTransactionId(transactionId);
        if (!scoredTransactionRepository.existsById(boundedTransactionId)) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }

        String submittedBy = currentAnalystUser.get()
                .map(principal -> principal.userId() == null ? "" : principal.userId().trim())
                .filter(userId -> !userId.isBlank())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Authentication is required."));

        String keyHash = idempotencyKeyPolicy.hashKey(idempotencyKey);
        ValidatedFeedback validated = validate(request);
        String requestPayloadHash = requestPayloadHash(validated);
        var existing = feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(
                submittedBy,
                boundedTransactionId,
                keyHash
        );
        if (existing.isPresent()) {
            return existingFeedbackResponse(existing.get(), requestPayloadHash);
        }

        return transactionRunner.runLocalCommit(() -> createFeedback(
                boundedTransactionId,
                submittedBy,
                keyHash,
                requestPayloadHash,
                validated
        ));
    }

    private EngineIntelligenceFeedbackResponse createFeedback(
            String boundedTransactionId,
            String submittedBy,
            String keyHash,
            String requestPayloadHash,
            ValidatedFeedback validated
    ) {
        Instant now = Instant.now(clock);
        EngineIntelligenceFeedbackDocument document = new EngineIntelligenceFeedbackDocument(
                UUID.randomUUID().toString(),
                boundedTransactionId,
                validated.engineIntelligenceAvailable(),
                validated.feedbackType(),
                validated.usefulness(),
                validated.accuracyAssessment(),
                validated.selectedReasonCodes(),
                submittedBy,
                now,
                UUID.randomUUID().toString(),
                keyHash,
                requestPayloadHash,
                now
        );

        PersistedFeedback persisted = saveIdempotently(document);
        if (!persisted.created()) {
            return existingFeedbackResponse(persisted.document(), requestPayloadHash);
        }
        try {
            auditFeedback(persisted.document());
        } catch (RuntimeException exception) {
            compensateUnauditedFeedback(persisted.document(), exception);
            throw exception;
        }
        return EngineIntelligenceFeedbackResponse.created(persisted.document());
    }

    private PersistedFeedback saveIdempotently(EngineIntelligenceFeedbackDocument document) {
        try {
            return new PersistedFeedback(feedbackRepository.save(document), true);
        } catch (DuplicateKeyException exception) {
            return feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(
                            document.getSubmittedBy(),
                            document.getTransactionId(),
                            document.getIdempotencyKeyHash()
                    )
                    .map(existing -> new PersistedFeedback(existing, false))
                    .orElseThrow(() -> exception);
        }
    }

    private EngineIntelligenceFeedbackResponse existingFeedbackResponse(
            EngineIntelligenceFeedbackDocument existing,
            String requestPayloadHash
    ) {
        if (!Objects.equals(existing.getRequestPayloadHash(), requestPayloadHash)) {
            throw new ConflictingIdempotencyKeyException();
        }
        return EngineIntelligenceFeedbackResponse.existing(existing);
    }

    private void compensateUnauditedFeedback(EngineIntelligenceFeedbackDocument document, RuntimeException exception) {
        if (transactionRunner.mode() != RegulatedMutationTransactionMode.OFF) {
            return;
        }
        try {
            feedbackRepository.deleteById(document.getFeedbackId());
        } catch (RuntimeException cleanupException) {
            exception.addSuppressed(cleanupException);
        }
    }

    private void auditFeedback(EngineIntelligenceFeedbackDocument document) {
        auditService.audit(
                AuditAction.SUBMIT_ENGINE_INTELLIGENCE_FEEDBACK,
                AuditResourceType.ENGINE_INTELLIGENCE_FEEDBACK,
                document.getFeedbackId(),
                document.getCorrelationId(),
                document.getSubmittedBy(),
                AuditOutcome.SUCCESS,
                null,
                feedbackAuditSummary(document),
                document.getFeedbackId()
        );
    }

    private AuditEventMetadataSummary feedbackAuditSummary(EngineIntelligenceFeedbackDocument document) {
        return new AuditEventMetadataSummary(
                document.getCorrelationId(),
                document.getFeedbackId(),
                "alert-service",
                "1.0",
                null,
                null,
                "ENGINE_INTELLIGENCE_FEEDBACK_SUBMIT",
                "transaction_id=" + document.getTransactionId()
                        + ";feedback_type=" + document.getFeedbackType()
                        + ";usefulness=" + document.getUsefulness()
                        + ";accuracy=" + document.getAccuracyAssessment()
                        + ";submitted_at=" + document.getSubmittedAt(),
                1
        );
    }

    private String normalizedTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        String normalized = transactionId.trim();
        if (normalized.length() > MAX_TRANSACTION_ID_LENGTH
                || transactionId.chars().anyMatch(Character::isISOControl)
                || !TRANSACTION_ID_PATTERN.matcher(normalized).matches()) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        return normalized;
    }

    private ValidatedFeedback validate(EngineIntelligenceFeedbackRequest request) {
        List<String> errors = new ArrayList<>();
        if (request == null) {
            throw new InvalidEngineIntelligenceFeedbackRequestException(List.of("body: required"));
        }
        if (!request.unknownFields().isEmpty()) {
            errors.add("request: contains unknown fields");
        }

        EngineIntelligenceFeedbackType feedbackType = enumValue(
                EngineIntelligenceFeedbackType.class,
                request.feedbackType(),
                "feedbackType",
                errors
        );
        EngineIntelligenceFeedbackUsefulness usefulness = enumValue(
                EngineIntelligenceFeedbackUsefulness.class,
                request.usefulness(),
                "usefulness",
                errors
        );
        EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment = enumValue(
                EngineIntelligenceFeedbackAccuracyAssessment.class,
                request.accuracyAssessment(),
                "accuracyAssessment",
                errors
        );
        if (request.engineIntelligenceAvailable() == null) {
            errors.add("engineIntelligenceAvailable: required");
        }

        List<String> reasonCodes = normalizeReasonCodes(request.selectedReasonCodes(), errors);

        if (!errors.isEmpty()) {
            throw new InvalidEngineIntelligenceFeedbackRequestException(errors);
        }
        return new ValidatedFeedback(
                feedbackType,
                usefulness,
                accuracyAssessment,
                request.engineIntelligenceAvailable(),
                reasonCodes
        );
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumType, String rawValue, String field, List<String> errors) {
        String normalized = normalizeAcceptedString(rawValue, field, errors);
        if (normalized == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException exception) {
            errors.add(field + ": invalid value");
            return null;
        }
    }

    private List<String> normalizeReasonCodes(List<String> rawReasonCodes, List<String> errors) {
        if (rawReasonCodes == null) {
            return List.of();
        }
        if (rawReasonCodes.size() > MAX_REASON_CODES) {
            errors.add("selectedReasonCodes: max " + MAX_REASON_CODES);
        }
        List<String> normalized = new ArrayList<>();
        for (int index = 0; index < rawReasonCodes.size(); index++) {
            String value = normalizeAcceptedString(rawReasonCodes.get(index), "selectedReasonCodes[" + index + "]", errors);
            if (value != null) {
                if (ACCURACY_ASSESSMENT_NAMES.contains(value)) {
                    errors.add("selectedReasonCodes[" + index + "]: invalid reason code");
                } else {
                    normalized.add(value);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeAcceptedString(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + ": required");
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ACCEPTED_STRING_LENGTH || normalized.chars().anyMatch(Character::isISOControl)) {
            errors.add(field + ": invalid value");
            return null;
        }
        if (containsForbiddenTerm(normalized)) {
            errors.add(field + ": forbidden value");
            return null;
        }
        return normalized;
    }

    private boolean containsForbiddenTerm(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return FORBIDDEN_TERMS.stream()
                .map(term -> term.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                .anyMatch(compact::contains);
    }

    private String requestPayloadHash(ValidatedFeedback validated) {
        return IdempotencyCanonicalHasher.hash(Map.of(
                "feedbackType", validated.feedbackType().name(),
                "usefulness", validated.usefulness().name(),
                "accuracyAssessment", validated.accuracyAssessment().name(),
                "engineIntelligenceAvailable", validated.engineIntelligenceAvailable(),
                "selectedReasonCodes", validated.selectedReasonCodes()
        ));
    }

    private record ValidatedFeedback(
            EngineIntelligenceFeedbackType feedbackType,
            EngineIntelligenceFeedbackUsefulness usefulness,
            EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment,
            boolean engineIntelligenceAvailable,
            List<String> selectedReasonCodes
    ) {
        private ValidatedFeedback {
            selectedReasonCodes = selectedReasonCodes == null ? List.of() : List.copyOf(selectedReasonCodes);
        }
    }

    private record PersistedFeedback(EngineIntelligenceFeedbackDocument document, boolean created) {
    }
}
