package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.observability.FraudCaseReadModelMetrics;
import com.frauddetection.alert.observability.FraudCaseReadModelOutcome;
import com.frauddetection.alert.observability.FraudCaseReadModelOutcomeClassifier;
import com.frauddetection.alert.service.FraudCaseEvidenceSummaryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fraud-cases")
public class FraudCaseEvidenceSummaryController {

    private final FraudCaseEvidenceSummaryService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;
    private final FraudCaseReadModelMetrics metrics;

    public FraudCaseEvidenceSummaryController(
            FraudCaseEvidenceSummaryService service,
            SensitiveReadAuditService sensitiveReadAuditService,
            FraudCaseReadModelMetrics metrics
    ) {
        this.service = service;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
        this.metrics = metrics;
    }

    @AuditedSensitiveRead
    @GetMapping("/{caseId}/evidence-summary")
    public FraudCaseEvidenceSummaryResponse summary(@PathVariable String caseId, HttpServletRequest request) {
        try {
            FraudCaseEvidenceSummaryResponse response = service.summary(caseId);
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_SUMMARY,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    response.evidenceItemCount(),
                    request
            );
            recordMetric(FraudCaseReadModelOutcomeClassifier.classifySummary(response));
            return response;
        } catch (FraudCaseNotFoundException exception) {
            recordMetric(FraudCaseReadModelOutcome.NOT_FOUND);
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_SUMMARY,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    ReadAccessAuditOutcome.REJECTED,
                    request
            );
            throw exception;
        } catch (RuntimeException exception) {
            recordMetric(FraudCaseReadModelOutcome.ERROR);
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_SUMMARY,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    ReadAccessAuditOutcome.FAILED,
                    request
            );
            throw exception;
        }
    }

    private void recordMetric(FraudCaseReadModelOutcome outcome) {
        try {
            metrics.recordEvidenceSummary(outcome);
        } catch (RuntimeException ignored) {
            // Metrics are operational telemetry only and must not change read or audit behavior.
        }
    }
}
