package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;
import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.service.FraudCaseEvidenceTimelineService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fraud-cases")
public class FraudCaseEvidenceTimelineController {

    private final FraudCaseEvidenceTimelineService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public FraudCaseEvidenceTimelineController(
            FraudCaseEvidenceTimelineService service,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.service = service;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @AuditedSensitiveRead
    @GetMapping("/{caseId}/evidence-timeline")
    public FraudCaseEvidenceTimelineResponse timeline(@PathVariable String caseId, HttpServletRequest request) {
        try {
            FraudCaseEvidenceTimelineResponse response = service.timeline(caseId);
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    response.events().size(),
                    request
            );
            return response;
        } catch (FraudCaseNotFoundException exception) {
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    ReadAccessAuditOutcome.REJECTED,
                    request
            );
            throw exception;
        } catch (RuntimeException exception) {
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    ReadAccessAuditOutcome.FAILED,
                    request
            );
            throw exception;
        }
    }
}
