package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
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

    public FraudCaseEvidenceSummaryController(
            FraudCaseEvidenceSummaryService service,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.service = service;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
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
            return response;
        } catch (FraudCaseNotFoundException exception) {
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_SUMMARY,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    ReadAccessAuditOutcome.REJECTED,
                    request
            );
            throw exception;
        } catch (RuntimeException exception) {
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
}
