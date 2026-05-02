package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.service.FraudCaseManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/fraud-cases")
public class FraudCaseController {

    private final FraudCaseManagementService fraudCaseManagementService;
    private final FraudCaseResponseMapper responseMapper;

    public FraudCaseController(FraudCaseManagementService fraudCaseManagementService, FraudCaseResponseMapper responseMapper) {
        this.fraudCaseManagementService = fraudCaseManagementService;
        this.responseMapper = responseMapper;
    }

    @GetMapping
    public PagedResponse<FraudCaseResponse> listCases(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "4") @Min(1) @Max(20) int size
    ) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = fraudCaseManagementService.listCases(pageable);
        return new PagedResponse<>(
                result.getContent().stream().map(responseMapper::toResponse).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    @GetMapping("/{caseId}")
    public FraudCaseResponse getCase(@PathVariable String caseId) {
        return responseMapper.toResponse(fraudCaseManagementService.getCase(caseId));
    }

    @PatchMapping("/{caseId}")
    public UpdateFraudCaseResponse updateCase(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody UpdateFraudCaseRequest request
    ) {
        return fraudCaseManagementService.updateCase(caseId, request, idempotencyKey);
    }
}
