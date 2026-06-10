package com.frauddetection.alert.governance.shadowperformance;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShadowPerformanceSummaryReadService {

    private final ShadowPerformanceSummaryProvider provider;
    private final ShadowPerformanceSummaryValidator validator;

    public ShadowPerformanceSummaryReadService(
            ShadowPerformanceSummaryProvider provider,
            ShadowPerformanceSummaryValidator validator
    ) {
        this.provider = provider;
        this.validator = validator;
    }

    public ShadowPerformanceSummaryResponse currentSummary() {
        ShadowPerformanceSummary summary;
        try {
            summary = provider.currentSummary()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shadow performance summary not found."));
        } catch (ShadowPerformanceSummaryProviderUnavailableException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Shadow performance summary unavailable.");
        }
        try {
            validator.validate(summary);
        } catch (ShadowPerformanceSummaryValidationException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Shadow performance summary is invalid.");
        }
        return ShadowPerformanceSummaryResponse.from(summary);
    }
}
