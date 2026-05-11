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
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
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
import java.util.Locale;
import java.util.List;
import java.util.Set;

@RestController
@Validated
@RequestMapping({"/api/v1/fraud-cases", "/api/fraud-cases"})
public class FraudCaseController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> LIST_QUERY_PARAMS = Set.of(
            "page",
            "size",
            "sort",
            "status",
            "assignee",
            "assignedInvestigatorId",
            "priority",
            "riskLevel",
            "createdFrom",
            "createdTo",
            "updatedFrom",
            "updatedTo",
            "linkedAlertId"
    );
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "priority", "riskLevel", "caseNumber");

    private final FraudCaseManagementService fraudCaseManagementService;
    private final FraudCaseResponseMapper responseMapper;
    private final AlertServiceMetrics metrics;

    public FraudCaseController(
            FraudCaseManagementService fraudCaseManagementService,
            FraudCaseResponseMapper responseMapper,
            AlertServiceMetrics metrics
    ) {
        this.fraudCaseManagementService = fraudCaseManagementService;
        this.responseMapper = responseMapper;
        this.metrics = metrics;
    }

    @GetMapping
    public PagedResponse<FraudCaseWorkQueueItemResponse> listCases(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
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
            @RequestParam MultiValueMap<String, String> requestParams
    ) {
        validateAllowedParameters(requestParams);
        validatePagination(page, size);
        validateRange("createdAt", createdFrom, createdTo);
        validateRange("updatedAt", updatedFrom, updatedTo);
        String normalizedAssignee = assignee(assignee, assignedInvestigatorId);
        Sort.Order sortOrder = sortOrder(sort);
        var pageable = PageRequest.of(page, size, Sort.by(sortOrder));
        metrics.recordFraudCaseWorkQueueRequest("success");
        metrics.recordFraudCaseWorkQueuePageSize(size);
        var result = fraudCaseManagementService.workQueue(
                status,
                normalizedAssignee,
                priority,
                riskLevel,
                createdFrom,
                createdTo,
                updatedFrom,
                updatedTo,
                linkedAlertId,
                pageable
        );
        metrics.recordFraudCaseWorkQueueQuery("success", sortOrder.getProperty());
        return new PagedResponse<>(
                result.getContent(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
            );
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

    private void validateAllowedParameters(MultiValueMap<String, String> requestParams) {
        List<String> unsupported = requestParams.keySet().stream()
                .filter(param -> !LIST_QUERY_PARAMS.contains(param))
                .toList();
        if (!unsupported.isEmpty()) {
            metrics.recordFraudCaseWorkQueueRequest("invalid_filter");
            throw new FraudCaseWorkQueueQueryException("UNSUPPORTED_FILTER", "Unsupported fraud case work queue filter.");
        }
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            metrics.recordFraudCaseWorkQueueRequest("invalid_filter");
            throw new FraudCaseWorkQueueQueryException("INVALID_PAGE_REQUEST", "Invalid fraud case work queue page request.");
        }
    }

    private void validateRange(String field, Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            metrics.recordFraudCaseWorkQueueRequest("invalid_filter");
            throw new FraudCaseWorkQueueQueryException("INVALID_FILTER_RANGE", "Invalid " + field + " filter range.");
        }
    }

    private String assignee(String assignee, String assignedInvestigatorId) {
        if (StringUtils.hasText(assignee)
                && StringUtils.hasText(assignedInvestigatorId)
                && !assignee.equals(assignedInvestigatorId)) {
            metrics.recordFraudCaseWorkQueueRequest("invalid_filter");
            throw new FraudCaseWorkQueueQueryException("INVALID_FILTER", "Conflicting assignee filters.");
        }
        return StringUtils.hasText(assignedInvestigatorId) ? assignedInvestigatorId : assignee;
    }

    private Sort.Order sortOrder(String sort) {
        String value = StringUtils.hasText(sort) ? sort.trim() : "createdAt,desc";
        String[] parts = value.split(",");
        if (parts.length > 2 || !SORT_FIELDS.contains(parts[0])) {
            metrics.recordFraudCaseWorkQueueRequest("invalid_sort");
            metrics.recordFraudCaseWorkQueueQuery("invalid_sort", "default");
            throw new FraudCaseWorkQueueQueryException("UNSUPPORTED_SORT_FIELD", "Unsupported fraud case work queue sort field.");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            try {
                direction = Sort.Direction.fromString(parts[1].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                metrics.recordFraudCaseWorkQueueRequest("invalid_sort");
                metrics.recordFraudCaseWorkQueueQuery("invalid_sort", parts[0]);
                throw new FraudCaseWorkQueueQueryException("UNSUPPORTED_SORT_DIRECTION", "Unsupported fraud case work queue sort direction.");
            }
        }
        return new Sort.Order(direction, parts[0]);
    }
}
