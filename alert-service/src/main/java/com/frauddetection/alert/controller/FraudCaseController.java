package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.fraudcase.FraudCaseReadQueryPolicy;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Validated
public class FraudCaseController {

    private static final String VERSIONED_BASE_PATH = "/api/v1/fraud-cases";

    private final FraudCaseManagementService fraudCaseManagementService;
    private final FraudCaseResponseMapper responseMapper;
    private final AlertServiceMetrics metrics;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public FraudCaseController(
            FraudCaseManagementService fraudCaseManagementService,
            FraudCaseResponseMapper responseMapper,
            AlertServiceMetrics metrics,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.fraudCaseManagementService = fraudCaseManagementService;
        this.responseMapper = responseMapper;
        this.metrics = metrics;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @AuditedSensitiveRead
    @GetMapping(VERSIONED_BASE_PATH + "/work-queue")
    public FraudCaseWorkQueueSliceResponse workQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = FraudCaseReadQueryPolicy.DEFAULT_SORT) String sort,
            @RequestParam(required = false) FraudCaseStatus status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String assignedInvestigatorId,
            @RequestParam(required = false) FraudCasePriority priority,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) Instant updatedFrom,
            @RequestParam(required = false) Instant updatedTo,
            @RequestParam(required = false) String linkedAlertId,
            HttpServletRequest request,
            @RequestParam MultiValueMap<String, String> requestParams
    ) {
        Sort.Order sortOrder = null;
        try {
            FraudCaseReadQueryPolicy.validateWorkQueueAllowedParameters(requestParams);
            FraudCaseReadQueryPolicy.validateWorkQueueSingleValueParameters(requestParams);
            FraudCaseReadQueryPolicy.validateWorkQueuePagination(page, size);
            String cursor = firstValue(requestParams, "cursor");
            FraudCaseReadQueryPolicy.validateCursorPageCombination(cursor, page);
            FraudCaseReadQueryPolicy.validateWorkQueueStringFilters(assignee, assignedInvestigatorId, linkedAlertId, sort, cursor);
            FraudCaseReadQueryPolicy.validateRange("createdAt", createdFrom, createdTo);
            FraudCaseReadQueryPolicy.validateRange("updatedAt", updatedFrom, updatedTo);
            String normalizedAssignee = assignee(assignee, assignedInvestigatorId);
            sortOrder = FraudCaseReadQueryPolicy.workQueueSortOrder(sort);
            var result = StringUtils.hasText(cursor)
                    ? fraudCaseManagementService.workQueue(
                    status,
                    normalizedAssignee,
                    priority,
                    riskLevel,
                    createdFrom,
                    createdTo,
                    updatedFrom,
                    updatedTo,
                    linkedAlertId,
                    FraudCaseReadQueryPolicy.boundedWorkQueuePageable(0, size, sortOrder),
                    cursor,
                    sortOrder
            )
                    : fraudCaseManagementService.workQueue(
                    status,
                    normalizedAssignee,
                    priority,
                    riskLevel,
                    createdFrom,
                    createdTo,
                    updatedFrom,
                    updatedTo,
                    linkedAlertId,
                    FraudCaseReadQueryPolicy.boundedWorkQueuePageable(page, size, sortOrder)
            );
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE,
                    ReadAccessResourceType.FRAUD_CASE,
                    null,
                    result.content().size(),
                    request
            );
            metrics.recordFraudCaseWorkQueueRequest("success");
            metrics.recordFraudCaseWorkQueuePageSize(size);
            metrics.recordFraudCaseWorkQueueQuery("success", sortOrder.getProperty());
            return result;
        } catch (FraudCaseWorkQueueQueryException exception) {
            recordInvalidWorkQueueQuery(exception, sort);
            auditRejectedWorkQueueRead(request);
            throw exception;
        } catch (RuntimeException exception) {
            metrics.recordFraudCaseWorkQueueRequest("failure");
            metrics.recordFraudCaseWorkQueueQuery("failure", sortOrder == null ? "default" : sortOrder.getProperty());
            auditFailedWorkQueueRead(request);
            throw exception;
        }
    }

    @AuditedSensitiveRead
    @GetMapping(VERSIONED_BASE_PATH + "/{caseId}")
    public FraudCaseResponse getCase(@PathVariable String caseId, HttpServletRequest request) {
        try {
            FraudCaseResponse response = responseMapper.toResponse(fraudCaseManagementService.getCase(caseId));
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.FRAUD_CASE_DETAIL,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    1,
                    request
            );
            return response;
        } catch (FraudCaseNotFoundException exception) {
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.FRAUD_CASE_DETAIL,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    ReadAccessAuditOutcome.REJECTED,
                    request
            );
            throw exception;
        } catch (RuntimeException exception) {
            sensitiveReadAuditService.auditAttempt(
                    ReadAccessEndpointCategory.FRAUD_CASE_DETAIL,
                    ReadAccessResourceType.FRAUD_CASE,
                    caseId,
                    ReadAccessAuditOutcome.FAILED,
                    request
            );
            throw exception;
        }
    }

    @PatchMapping(VERSIONED_BASE_PATH + "/{caseId}")
    public UpdateFraudCaseResponse updateCase(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody UpdateFraudCaseRequest request
    ) {
        return fraudCaseManagementService.updateCase(caseId, request, idempotencyKey);
    }

    private String assignee(String assignee, String assignedInvestigatorId) {
        String normalizedAssignee = normalize(assignee);
        String normalizedAssignedInvestigatorId = normalize(assignedInvestigatorId);
        if (StringUtils.hasText(normalizedAssignee)
                && StringUtils.hasText(normalizedAssignedInvestigatorId)
                && !normalizedAssignee.equals(normalizedAssignedInvestigatorId)) {
            throw new FraudCaseWorkQueueQueryException("INVALID_FILTER", "Conflicting assignee filters.");
        }
        return StringUtils.hasText(normalizedAssignedInvestigatorId) ? normalizedAssignedInvestigatorId : normalizedAssignee;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void auditRejectedWorkQueueRead(HttpServletRequest request) {
        sensitiveReadAuditService.auditAttempt(
                ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE,
                ReadAccessResourceType.FRAUD_CASE,
                null,
                ReadAccessAuditOutcome.REJECTED,
                request
        );
    }

    private void auditFailedWorkQueueRead(HttpServletRequest request) {
        sensitiveReadAuditService.auditAttempt(
                ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE,
                ReadAccessResourceType.FRAUD_CASE,
                null,
                ReadAccessAuditOutcome.FAILED,
                request
        );
    }

    private void recordInvalidWorkQueueQuery(FraudCaseWorkQueueQueryException exception, String sort) {
        if ("INVALID_CURSOR".equals(exception.code()) || "INVALID_CURSOR_PAGE_COMBINATION".equals(exception.code())) {
            metrics.recordFraudCaseWorkQueueRequest("invalid_cursor");
            metrics.recordFraudCaseWorkQueueQuery("invalid_cursor", invalidSortMetricField(sort));
            return;
        }
        if (exception.code().startsWith("UNSUPPORTED_SORT")) {
            metrics.recordFraudCaseWorkQueueRequest("invalid_sort");
            metrics.recordFraudCaseWorkQueueQuery("invalid_sort", invalidSortMetricField(sort));
            return;
        }
        metrics.recordFraudCaseWorkQueueRequest("invalid_filter");
    }

    private String invalidSortMetricField(String sort) {
        if (!StringUtils.hasText(sort)) {
            return "default";
        }
        String field = sort.trim().split(",")[0];
        return StringUtils.hasText(field) && FraudCaseReadQueryPolicy.SORT_FIELDS.contains(field) ? field : "default";
    }

    private String firstValue(MultiValueMap<String, String> requestParams, String key) {
        return requestParams == null ? null : requestParams.getFirst(key);
    }

}
