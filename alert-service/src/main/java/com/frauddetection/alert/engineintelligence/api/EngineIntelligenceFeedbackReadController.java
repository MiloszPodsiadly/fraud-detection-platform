package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackReadMetricReason;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/transactions/scored")
public class EngineIntelligenceFeedbackReadController {

    private final EngineIntelligenceFeedbackReadService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;
    private final EngineIntelligenceFeedbackReadQueryPolicy queryPolicy;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceFeedbackReadController(
            EngineIntelligenceFeedbackReadService service,
            SensitiveReadAuditService sensitiveReadAuditService,
            EngineIntelligenceFeedbackReadQueryPolicy queryPolicy,
            AlertServiceMetrics metrics
    ) {
        this(service, sensitiveReadAuditService, queryPolicy, metrics, Clock.systemUTC());
    }

    EngineIntelligenceFeedbackReadController(
            EngineIntelligenceFeedbackReadService service,
            SensitiveReadAuditService sensitiveReadAuditService,
            EngineIntelligenceFeedbackReadQueryPolicy queryPolicy,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.sensitiveReadAuditService = Objects.requireNonNull(sensitiveReadAuditService, "sensitiveReadAuditService is required");
        this.queryPolicy = Objects.requireNonNull(queryPolicy, "queryPolicy is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @GetMapping("/{transactionId}/engine-intelligence/feedback")
    @AuditedSensitiveRead
    public EngineIntelligenceFeedbackReadModel read(
            @PathVariable String transactionId,
            @RequestParam MultiValueMap<String, String> rawParams,
            HttpServletRequest request
    ) {
        Instant startedAt = clock.instant();
        metrics.recordEngineIntelligenceFeedbackReadAttempt();
        try {
            int limit = queryPolicy.limit(rawParams);
            EngineIntelligenceFeedbackReadModel response = service.read(transactionId, limit);
            try {
                sensitiveReadAuditService.audit(
                        ReadAccessEndpointCategory.ENGINE_INTELLIGENCE_FEEDBACK_READ,
                        ReadAccessResourceType.ENGINE_INTELLIGENCE_FEEDBACK,
                        response.transactionId(),
                        response.feedback().size(),
                        request
                );
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() == 503) {
                    metrics.recordEngineIntelligenceFeedbackReadAuditFailure();
                    metrics.recordEngineIntelligenceFeedbackReadUnavailable(
                            EngineIntelligenceFeedbackReadMetricReason.AUDIT_FAILURE
                    );
                }
                throw exception;
            } catch (RuntimeException exception) {
                metrics.recordEngineIntelligenceFeedbackReadAuditFailure();
                metrics.recordEngineIntelligenceFeedbackReadUnavailable(
                        EngineIntelligenceFeedbackReadMetricReason.AUDIT_FAILURE
                );
                throw exception;
            }
            if (response.feedback().isEmpty()) {
                metrics.recordEngineIntelligenceFeedbackReadEmpty();
            } else {
                metrics.recordEngineIntelligenceFeedbackReadSuccess();
            }
            return response;
        } catch (InvalidEngineIntelligenceFeedbackRequestException exception) {
            metrics.recordEngineIntelligenceFeedbackReadValidationFailure();
            throw exception;
        } catch (EngineIntelligenceScoredTransactionNotFoundException exception) {
            metrics.recordEngineIntelligenceFeedbackReadValidationFailure();
            throw exception;
        } catch (EngineIntelligenceFeedbackReadUnavailableException exception) {
            metrics.recordEngineIntelligenceFeedbackReadUnavailable(exception.metricReason());
            throw exception;
        } finally {
            metrics.recordEngineIntelligenceFeedbackReadLatency(Duration.between(startedAt, clock.instant()));
        }
    }
}
