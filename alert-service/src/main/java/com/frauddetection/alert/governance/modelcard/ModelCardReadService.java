package com.frauddetection.alert.governance.modelcard;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelCardReadService {

    private final ModelCardProvider provider;
    private final ModelCardValidator validator;
    private final ModelCardReadMetrics metrics;

    public ModelCardReadService(
            ModelCardProvider provider,
            ModelCardValidator validator,
            ModelCardReadMetrics metrics
    ) {
        this.provider = provider;
        this.validator = validator;
        this.metrics = metrics;
    }

    public ModelCardResponse currentCard() {
        ModelCard card;
        try {
            card = provider.currentCard().orElseThrow(() -> {
                metrics.record(ModelCardReadMetrics.Outcome.NOT_FOUND);
                return new ResponseStatusException(HttpStatus.NOT_FOUND, "Model Card not found.");
            });
        } catch (ModelCardProviderUnavailableException exception) {
            metrics.record(ModelCardReadMetrics.Outcome.UNAVAILABLE);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Model Card unavailable.");
        }
        try {
            validator.validate(card);
        } catch (ModelCardValidationException exception) {
            metrics.record(ModelCardReadMetrics.Outcome.INVALID);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Model Card is invalid.");
        }
        metrics.record(ModelCardReadMetrics.Outcome.SUCCESS);
        return ModelCardResponse.from(card);
    }
}
