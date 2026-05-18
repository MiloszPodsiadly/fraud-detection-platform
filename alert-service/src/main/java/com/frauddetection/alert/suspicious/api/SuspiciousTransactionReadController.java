package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/internal/suspicious-transactions")
public class SuspiciousTransactionReadController {

    private final SuspiciousTransactionReadService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;
    private final AlertServiceMetrics metrics;

    public SuspiciousTransactionReadController(
            SuspiciousTransactionReadService service,
            SensitiveReadAuditService sensitiveReadAuditService,
            AlertServiceMetrics metrics
    ) {
        this.service = service;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
        this.metrics = metrics;
    }

    @GetMapping
    @AuditedSensitiveRead
    public PagedResponse<SuspiciousTransactionResponse> search(
            @RequestParam MultiValueMap<String, String> rawParams,
            HttpServletRequest request
    ) {
        SuspiciousTransactionSearchQuery query;
        try {
            query = SuspiciousTransactionSearchQuery.from(rawParams);
        } catch (SuspiciousTransactionReadValidationException exception) {
            metrics.recordSuspiciousTransactionApiSearch("validation_error", "ANY", "ANY");
            throw exception;
        }
        try {
            Page<SuspiciousTransactionResponse> result = service.search(query);
            PagedResponse<SuspiciousTransactionResponse> response = new PagedResponse<>(
                    result.getContent(),
                    result.getTotalElements(),
                    result.getTotalPages(),
                    result.getNumber(),
                    result.getSize()
            );
            metrics.recordSuspiciousTransactionApiSearch("success", statusLabel(query), riskLevelLabel(query));
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SEARCH,
                    ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                    null,
                    response.content().size(),
                    request
            );
            return response;
        } catch (RuntimeException exception) {
            metrics.recordSuspiciousTransactionApiSearch("error", statusLabel(query), riskLevelLabel(query));
            throw exception;
        }
    }

    @GetMapping("/{suspiciousTransactionId}")
    @AuditedSensitiveRead
    public SuspiciousTransactionResponse findById(
            @PathVariable String suspiciousTransactionId,
            HttpServletRequest request
    ) {
        try {
            SuspiciousTransactionResponse response = service.findById(suspiciousTransactionId)
                    .orElseThrow(() -> notFound(suspiciousTransactionId, request));
            metrics.recordSuspiciousTransactionApiRead("success", statusLabel(response), riskLevelLabel(response));
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ,
                    ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                    suspiciousTransactionId,
                    1,
                    request
            );
            return response;
        } catch (ResponseStatusException exception) {
            metrics.recordSuspiciousTransactionApiRead("not_found", "ANY", "ANY");
            throw exception;
        } catch (RuntimeException exception) {
            metrics.recordSuspiciousTransactionApiRead("error", "ANY", "ANY");
            throw exception;
        }
    }

    private ResponseStatusException notFound(String suspiciousTransactionId, HttpServletRequest request) {
        sensitiveReadAuditService.auditAttempt(
                ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ,
                ReadAccessResourceType.SUSPICIOUS_TRANSACTION,
                suspiciousTransactionId,
                ReadAccessAuditOutcome.REJECTED,
                request
        );
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Suspicious transaction not found.");
    }

    private String statusLabel(SuspiciousTransactionSearchQuery query) {
        return query.status() == null ? "ANY" : query.status().name();
    }

    private String riskLevelLabel(SuspiciousTransactionSearchQuery query) {
        return query.riskLevel() == null ? "ANY" : query.riskLevel().name();
    }

    private String statusLabel(SuspiciousTransactionResponse response) {
        return response.status() == null ? "ANY" : response.status().name();
    }

    private String riskLevelLabel(SuspiciousTransactionResponse response) {
        return response.riskLevel() == null ? "ANY" : response.riskLevel().name();
    }
}
