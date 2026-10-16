package com.frauddetection.alert.governance.exactmodelshadowperformance;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class ExactModelShadowPerformanceReadService {

    private final ExactModelShadowPerformanceProvider provider;
    private final ExactModelShadowPerformanceReadMetrics metrics;

    public ExactModelShadowPerformanceReadService(
            ExactModelShadowPerformanceProvider provider,
            ExactModelShadowPerformanceReadMetrics metrics
    ) {
        this.provider = provider;
        this.metrics = metrics;
    }

    public JsonNode find(ExactModelShadowPerformanceSelector selector) {
        try {
            JsonNode summary = provider.find(selector).orElseThrow(() -> {
                metrics.record(ExactModelShadowPerformanceReadMetrics.Outcome.NOT_FOUND);
                return new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Exact model Shadow Performance artifact not found.");
            });
            metrics.record(ExactModelShadowPerformanceReadMetrics.Outcome.SUCCESS);
            return summary;
        } catch (ExactModelShadowPerformanceUnavailableException exception) {
            metrics.record(ExactModelShadowPerformanceReadMetrics.Outcome.UNAVAILABLE);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Exact model Shadow Performance artifact unavailable.");
        }
    }
}
