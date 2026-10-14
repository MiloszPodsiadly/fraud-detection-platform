package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCardValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelCardValidator validator = new ModelCardValidator();

    @Test
    void acceptsExactArtifactCardWithoutGrantingDecisionAuthority() {
        ModelCard card = ModelCardTestFixtures.validUnavailableCard(objectMapper);

        assertThatNoException().isThrownBy(() -> validator.validate(card));
    }

    @Test
    void rejectsUnknownSchemaAndPromotionState() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        ModelCard unknownSchema = copy(valid, "other-schema", valid.promotionState(), valid.evaluationEvidence());
        ModelCard promoted = copy(valid, valid.schemaVersion(), "APPROVED", valid.evaluationEvidence());

        assertThatThrownBy(() -> validator.validate(unknownSchema)).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validate(promoted)).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsUnknownEvidenceFieldsAndDecisionAuthority() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        var evidence = valid.evaluationEvidence().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) evidence).put("rawRows", "forbidden");
        ModelCard unknownEvidence = copy(valid, valid.schemaVersion(), valid.promotionState(), evidence);
        ModelCard authority = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), null,
                new ModelCard.VerifiedUsageBoundary(
                        valid.verifiedUsageBoundary().allowedModes(), "PRIMARY", "NONE", "NONE", "NONE", "NONE"
                )
        );

        assertThatThrownBy(() -> validator.validate(unknownEvidence)).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validate(authority)).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsSensitiveIdentityContent() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        ModelCard sensitive = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(),
                new ModelCard.Subject(
                        "ML_MODEL", "customerId_model", valid.subject().modelVersion(),
                        valid.subject().featureContractVersion(), valid.subject().modelArtifactSha256(), "COMPLETE"
                ),
                valid.promotionState(), valid.modelArtifactVerification(), valid.evaluationEvidence(), null,
                valid.verifiedUsageBoundary()
        );

        assertThatThrownBy(() -> validator.validate(sensitive)).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsEvaluationWindowWithReversedBounds() {
        var window = objectMapper.createObjectNode()
                .put("timeBasis", "FEEDBACK_CREATED_AT")
                .put("fromInclusive", "2026-10-14T10:00:00Z")
                .put("toInclusive", "2026-10-13T10:00:00Z");

        assertThatThrownBy(() -> validator.validateWindow(window))
                .isInstanceOf(ModelCardValidationException.class)
                .hasMessageContaining("fromInclusive must not be later than");
    }

    private ModelCard copy(
            ModelCard source,
            String schema,
            String promotionState,
            tools.jackson.databind.JsonNode evidence
    ) {
        return new ModelCard(
                schema, source.cardType(), source.generatedAt(), source.subject(), promotionState,
                source.modelArtifactVerification(), evidence, source.evaluationSummary(), source.verifiedUsageBoundary()
        );
    }
}
