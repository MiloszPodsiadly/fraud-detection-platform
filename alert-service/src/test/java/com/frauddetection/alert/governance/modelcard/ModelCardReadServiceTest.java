package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCardReadServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelCardValidator validator =
            new ModelCardValidator(new ModelEvaluationGovernanceValidator());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ModelCardReadMetrics metrics = new ModelCardReadMetrics(meterRegistry);
    private final ModelCardProvider provider = mock(ModelCardProvider.class);
    private final ModelCardReadService service = new ModelCardReadService(provider, validator, metrics);

    @Test
    void returnsValidatedCardAndRecordsBoundedSuccessOutcome() {
        when(provider.currentCard()).thenReturn(Optional.of(ModelCardTestFixtures.validUnavailableCard(objectMapper)));

        ModelCardResponse response = service.currentCard();

        assertThat(response.promotionState()).isEqualTo("NOT_EVALUATED_FOR_PROMOTION");
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    void unconfiguredProviderMapsTo404() {
        when(provider.currentCard()).thenReturn(Optional.empty());

        assertThatThrownBy(service::currentCard)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(404));
        assertThat(counter("not_found")).isEqualTo(1.0);
    }

    @Test
    void providerFailureMapsToDetailSafe503() {
        when(provider.currentCard()).thenThrow(new ModelCardProviderUnavailableException("private path detail"));

        assertThatThrownBy(service::currentCard)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException response = (ResponseStatusException) exception;
                    assertThat(response.getStatusCode().value()).isEqualTo(503);
                    assertThat(response.getReason()).isEqualTo("Model Card unavailable.");
                });
        assertThat(counter("unavailable")).isEqualTo(1.0);
    }

    @Test
    void malformedProviderResultMapsToDetailSafe503() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        ModelCard malformed = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), valid.evaluationSummary(),
                new ModelCard.VerifiedUsageBoundary(
                        valid.verifiedUsageBoundary().allowedModes(),
                        valid.verifiedUsageBoundary().intendedUse(),
                        valid.verifiedUsageBoundary().notIntendedUse(),
                        valid.verifiedUsageBoundary().useLimitations(),
                        null, "NONE", "NONE", "NONE", "NONE"
                )
        );
        when(provider.currentCard()).thenReturn(Optional.of(malformed));

        assertThatThrownBy(service::currentCard)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException response = (ResponseStatusException) exception;
                    assertThat(response.getStatusCode().value()).isEqualTo(503);
                    assertThat(response.getReason()).isEqualTo("Model Card is invalid.");
                });
        assertThat(counter("invalid")).isEqualTo(1.0);
    }

    private double counter(String outcome) {
        return meterRegistry.get("governance_model_card_read_total").tag("outcome", outcome).counter().count();
    }
}
