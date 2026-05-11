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
import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    public FraudCaseController(FraudCaseManagementService fraudCaseManagementService, FraudCaseResponseMapper responseMapper) {
        this.fraudCaseManagementService = fraudCaseManagementService;
        this.responseMapper = responseMapper;
    }

    @GetMapping
    public PagedResponse<FraudCaseSummaryResponse> listCases(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) FraudCaseStatus status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) FraudCasePriority priority,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) String linkedAlertId
    ) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (!hasSearchFilters(status, assignee, priority, riskLevel, createdFrom, createdTo, linkedAlertId)) {
            var result = fraudCaseManagementService.listCases(pageable);
            return new PagedResponse<>(
                    result.getContent().stream().map(responseMapper::toSummary).toList(),
                    result.getTotalElements(),
                    result.getTotalPages(),
                    result.getNumber(),
                    result.getSize()
            );
        }
        var result = fraudCaseManagementService.searchCases(
                status,
                assignee,
                priority,
                riskLevel,
                createdFrom,
                createdTo,
                linkedAlertId,
                pageable
        );
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
        return responseMapper.toResponse(fraudCaseManagementService.createCase(request, idempotencyKey));
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
        return responseMapper.toResponse(fraudCaseManagementService.assignCase(caseId, request, idempotencyKey));
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
        return responseMapper.toResponse(fraudCaseManagementService.transitionCase(caseId, request, idempotencyKey));
    }

    @PostMapping("/{caseId}/close")
    public FraudCaseResponse close(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CloseFraudCaseRequest request
    ) {
        return responseMapper.toResponse(fraudCaseManagementService.closeCase(caseId, request, idempotencyKey));
    }

    @PostMapping("/{caseId}/reopen")
    public FraudCaseResponse reopen(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody ReopenFraudCaseRequest request
    ) {
        return responseMapper.toResponse(fraudCaseManagementService.reopenCase(caseId, request, idempotencyKey));
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
                || assignee != null
                || priority != null
                || riskLevel != null
                || createdFrom != null
                || createdTo != null
                || linkedAlertId != null;
    }
}
