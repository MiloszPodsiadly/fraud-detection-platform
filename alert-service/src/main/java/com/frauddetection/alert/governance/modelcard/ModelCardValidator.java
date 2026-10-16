package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;
import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ModelCardValidator {

    private static final String CARD_SCHEMA = "ml-model-card-v1";
    private static final String CARD_TYPE = "ML_MODEL_CARD_V1";
    private static final String EVALUATION_REPORT_TYPE = "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1";
    private static final String EVALUATION_ARTIFACT_SET =
            "ml-model-feedback-dataset-evaluation-artifact-set-v1";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern IDENTITY = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Set<String> FORBIDDEN_IDENTITY_TERMS = Set.of(
            "accountid", "apikey", "authorization", "bearer", "cardid", "correlationid", "customerid",
            "deviceid", "email", "endpoint", "exceptionmessage", "feedbackid", "finaldecision",
            "groundtruth", "idempotencykey", "merchantid", "metadata", "modeltraininglabel", "password",
            "paymentauthorization", "rawfeaturevector", "rawmlrequest", "rawmlresponse", "rawpayload",
            "rawrequest", "rawresponse", "requestpayloadhash", "secret", "stacktrace", "submittedby",
            "token", "traininglabel", "transactionid"
    );
    private static final List<String> INTENDED_USE = List.of(
            "EXACT_MODEL_OFFLINE_EVALUATION",
            "RULES_VS_ML_DIAGNOSTIC_COMPARISON",
            "SHADOW_MODEL_MONITORING"
    );
    private static final List<String> NOT_INTENDED_USE = List.of(
            "AUTOMATIC_APPROVE_DECLINE_OR_BLOCK",
            "MODEL_PROMOTION_APPROVAL",
            "PAYMENT_AUTHORIZATION",
            "PRIMARY_PRODUCTION_FRAUD_DECISIONING",
            "SCORING_THRESHOLD_CHANGE",
            "WORKFLOW_OR_ANALYST_RECOMMENDATION"
    );
    private static final List<String> USE_LIMITATIONS = List.of(
            "ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_GROUND_TRUTH",
            "BOUNDED_RETURNED_POPULATION_ONLY",
            "DOES_NOT_ESTABLISH_GLOBAL_DATASET_COMPLETENESS",
            "DOES_NOT_ESTABLISH_MODEL_CALIBRATION",
            "DOES_NOT_ESTABLISH_PRODUCTION_READINESS",
            "SOURCE_VERIFICATION_LEVEL_MUST_BE_INTERPRETED_EXPLICITLY"
    );

    private final ModelEvaluationGovernanceValidator evaluationValidator;

    public ModelCardValidator(ModelEvaluationGovernanceValidator evaluationValidator) {
        this.evaluationValidator = evaluationValidator;
    }

    public void validate(ModelCard card) {
        require(card != null, "model card is missing");
        require(CARD_SCHEMA.equals(card.schemaVersion()), "schemaVersion unsupported");
        require(CARD_TYPE.equals(card.cardType()), "cardType unsupported");
        timestamp(card.generatedAt(), "generatedAt");
        validateSubject(card.subject());
        require("NOT_EVALUATED_FOR_PROMOTION".equals(card.promotionState()), "promotionState unsupported");
        validateArtifactVerification(card);
        validateEvidenceAndSummary(card);
        validateUsageBoundary(card.verifiedUsageBoundary());
    }

    private void validateSubject(ModelCard.Subject subject) {
        require(subject != null, "subject is missing");
        require("ML_MODEL".equals(subject.subjectType()), "subjectType unsupported");
        identity(subject.modelName(), 64, "modelName");
        identity(subject.modelVersion(), 64, "modelVersion");
        identity(subject.featureContractVersion(), 96, "featureContractVersion");
        sha256(subject.modelArtifactSha256(), "modelArtifactSha256");
        require("COMPLETE".equals(subject.identityCompleteness()), "identityCompleteness unsupported");
    }

    private void validateArtifactVerification(ModelCard card) {
        ModelCard.ModelArtifactVerification verification = card.modelArtifactVerification();
        require(verification != null, "modelArtifactVerification is missing");
        require("VERIFIED_AGAINST_ARTIFACT_BYTES".equals(verification.status()),
                "modelArtifactVerification.status unsupported");
        require(card.subject().modelArtifactSha256().equals(verification.sha256()),
                "model artifact verification does not match subject");
    }

    private void validateEvidenceAndSummary(ModelCard card) {
        JsonNode evidence = card.evaluationEvidence();
        require(evidence != null && evidence.isObject(), "evaluationEvidence must be an object");
        String status = text(evidence, "status");
        if ("NOT_AVAILABLE".equals(status)) {
            exactFields(evidence, Set.of("status", "reason"), "evaluationEvidence");
            require("MODEL_EVALUATION_NOT_CONFIGURED".equals(text(evidence, "reason")),
                    "evaluationEvidence.reason unsupported");
            require(card.evaluationSummary() == null || card.evaluationSummary().isNull(),
                    "evaluationSummary must be null when evaluation is unavailable");
            return;
        }
        require("AVAILABLE".equals(status), "evaluationEvidence.status unsupported");
        exactFields(evidence, Set.of(
                "status", "reportType", "artifactSetVersion", "reportGeneratedAt", "manifestSha256",
                "integrityVerification", "sourceDatasetVerificationLevel"
        ), "evaluationEvidence");
        require(EVALUATION_REPORT_TYPE.equals(text(evidence, "reportType")),
                "evaluationEvidence.reportType unsupported");
        require(EVALUATION_ARTIFACT_SET.equals(text(evidence, "artifactSetVersion")),
                "evaluationEvidence.artifactSetVersion unsupported");
        timestamp(text(evidence, "reportGeneratedAt"), "evaluationEvidence.reportGeneratedAt");
        sha256(text(evidence, "manifestSha256"), "evaluationEvidence.manifestSha256");
        require("VERIFIED_REPORT_AND_MANIFEST".equals(text(evidence, "integrityVerification")),
                "evaluationEvidence.integrityVerification unsupported");
        require(Set.of("CLAIM_ONLY", "VERIFIED_AGAINST_SOURCE_BYTES")
                        .contains(text(evidence, "sourceDatasetVerificationLevel")),
                "evaluationEvidence.sourceDatasetVerificationLevel unsupported");
        evaluationValidator.validateEvaluationSummary(
                card.evaluationSummary(),
                new ModelEvaluationGovernanceValidator.ExpectedSubject(
                        card.subject().subjectType(),
                        card.subject().modelName(),
                        card.subject().modelVersion(),
                        card.subject().featureContractVersion(),
                        card.subject().modelArtifactSha256(),
                        card.subject().identityCompleteness()
                ),
                text(evidence, "reportGeneratedAt"),
                card.generatedAt()
        );
    }

    private void validateUsageBoundary(ModelCard.VerifiedUsageBoundary boundary) {
        require(boundary != null, "verifiedUsageBoundary is missing");
        require(boundary.allowedModes() != null
                        && List.of("COMPARE", "OFFLINE_EVALUATION", "SHADOW").equals(boundary.allowedModes()),
                "verifiedUsageBoundary.allowedModes unsupported");
        require(INTENDED_USE.equals(boundary.intendedUse()), "verifiedUsageBoundary.intendedUse unsupported");
        require(NOT_INTENDED_USE.equals(boundary.notIntendedUse()),
                "verifiedUsageBoundary.notIntendedUse unsupported");
        require(USE_LIMITATIONS.equals(boundary.useLimitations()),
                "verifiedUsageBoundary.useLimitations unsupported");
        require("NONE".equals(boundary.runtimeDecisionAuthority()),
                "verifiedUsageBoundary.runtimeDecisionAuthority must be NONE");
        require("NONE".equals(boundary.promotionAuthority()),
                "verifiedUsageBoundary.promotionAuthority must be NONE");
        require("NONE".equals(boundary.thresholdChangeAuthority()),
                "verifiedUsageBoundary.thresholdChangeAuthority must be NONE");
        require("NONE".equals(boundary.paymentAuthorizationAuthority()),
                "verifiedUsageBoundary.paymentAuthorizationAuthority must be NONE");
        require("NONE".equals(boundary.workflowAuthority()),
                "verifiedUsageBoundary.workflowAuthority must be NONE");
    }

    private void exactFields(JsonNode value, Set<String> expected, String label) {
        require(value != null && value.isObject() && value.size() == expected.size(), label + " field set unsupported");
        for (String field : expected) {
            require(value.has(field), label + " missing field");
        }
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isTextual(), field + " must be text");
        return node.textValue();
    }

    private void identity(String value, int maximum, String field) {
        require(value != null && !value.isBlank() && value.length() <= maximum && IDENTITY.matcher(value).matches(),
                field + " is invalid");
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        require(FORBIDDEN_IDENTITY_TERMS.stream().noneMatch(compact::contains), field + " contains forbidden value");
    }

    private void sha256(String value, String field) {
        require(value != null && SHA256.matcher(value).matches(), field + " must be lowercase SHA-256");
    }

    private void timestamp(String value, String field) {
        try {
            GovernanceTimestampContract.parse(value, field);
        } catch (IllegalArgumentException exception) {
            throw new ModelEvaluationValidationException(exception.getMessage());
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ModelEvaluationValidationException(message);
        }
    }
}
