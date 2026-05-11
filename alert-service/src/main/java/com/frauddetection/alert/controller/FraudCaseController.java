package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.FraudCaseAuditResponse;
import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.FraudCaseSummaryResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.fraudcase.FraudCaseReadQueryPolicy;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@Validated
@RequestMapping({"/api/v1/fraud-cases", "/api/fraud-cases"})
public class FraudCaseController {

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

    @GetMapping
    public PagedResponse<FraudCaseSummaryResponse> listCases(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) FraudCaseStatus status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) FraudCasePriority priority,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) String linkedAlertId
    ) {
        FraudCaseReadQueryPolicy.validateLegacyListPagination(page, size);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = hasSearchFilters(status, assignee, priority, riskLevel, createdFrom, createdTo, linkedAlertId)
                ? fraudCaseManagementService.searchCases(status, assignee, priority, riskLevel, createdFrom, createdTo, linkedAlertId, pageable)
                : fraudCaseManagementService.listCases(pageable).map(responseMapper::toSummary);
        return new PagedResponse<>(
                result.getContent(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    @AuditedSensitiveRead
    @GetMapping("/work-queue")
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

    @PostMapping
    public FraudCaseResponse createCase(
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CreateFraudCaseRequest request
    ) {
        return fraudCaseManagementService.createCase(request, idempotencyKey);
    }

    @GetMapping("/{caseId}")
    public FraudCaseResponse getCase(@PathVariable String caseId) {
        return responseMapper.toResponse(fraudCaseManagementService.getCase(caseId));
    }

    @PostMapping("/{caseId}/assign")
    public FraudCaseResponse assign(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody AssignFraudCaseRequest request
    ) {
        return fraudCaseManagementService.assignCase(caseId, request, idempotencyKey);
    }

    @PostMapping("/{caseId}/notes")
    public FraudCaseNoteResponse addNote(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody AddFraudCaseNoteRequest request
    ) {
        return fraudCaseManagementService.addNote(caseId, request, idempotencyKey);
    }

    @PostMapping("/{caseId}/decisions")
    public FraudCaseDecisionResponse addDecision(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody AddFraudCaseDecisionRequest request
    ) {
        return fraudCaseManagementService.addDecision(caseId, request, idempotencyKey);
    }

    @PostMapping("/{caseId}/transition")
    public FraudCaseResponse transition(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody TransitionFraudCaseRequest request
    ) {
        return fraudCaseManagementService.transitionCase(caseId, request, idempotencyKey);
    }

    @PostMapping("/{caseId}/close")
    public FraudCaseResponse close(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CloseFraudCaseRequest request
    ) {
        return fraudCaseManagementService.closeCase(caseId, request, idempotencyKey);
    }

    @PostMapping("/{caseId}/reopen")
    public FraudCaseResponse reopen(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody ReopenFraudCaseRequest request
    ) {
        return fraudCaseManagementService.reopenCase(caseId, request, idempotencyKey);
    }

    @GetMapping("/{caseId}/audit")
    public List<FraudCaseAuditResponse> audit(@PathVariable String caseId) {
        return fraudCaseManagementService.auditTrail(caseId);
    }

    @PatchMapping("/{caseId}")
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

    private boolean hasSearchFilters(
            FraudCaseStatus status,
            String assignee,
            FraudCasePriority priority,
            RiskLevel riskLevel,
            Instant createdFrom,
            Instant createdTo,
            String linkedAlertId
    ) {
        return status != null
                || StringUtils.hasText(assignee)
                || priority != null
                || riskLevel != null
                || createdFrom != null
                || createdTo != null
                || StringUtils.hasText(linkedAlertId);
    }
}
