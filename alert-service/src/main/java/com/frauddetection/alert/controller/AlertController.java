package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.AlertDetailsResponse;
import com.frauddetection.alert.api.AlertSummaryResponse;
import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.assistant.AnalystCaseSummaryRequest;
import com.frauddetection.alert.assistant.AnalystCaseSummaryResponse;
import com.frauddetection.alert.assistant.AnalystCaseSummaryUseCase;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.service.AlertManagementUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertManagementUseCase alertManagementUseCase;
    private final AnalystCaseSummaryUseCase analystCaseSummaryUseCase;
    private final AlertResponseMapper alertResponseMapper;

    public AlertController(
            AlertManagementUseCase alertManagementUseCase,
            AnalystCaseSummaryUseCase analystCaseSummaryUseCase,
            AlertResponseMapper alertResponseMapper
    ) {
        this.alertManagementUseCase = alertManagementUseCase;
        this.analystCaseSummaryUseCase = analystCaseSummaryUseCase;
        this.alertResponseMapper = alertResponseMapper;
    }

    @GetMapping
    public PagedResponse<AlertSummaryResponse> listAlerts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "alertTimestamp"));
        var result = alertManagementUseCase.listAlerts(pageable);
        return new PagedResponse<>(
                result.getContent().stream().map(alertResponseMapper::toSummary).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    @GetMapping("/{alertId}")
    public AlertDetailsResponse getAlert(@PathVariable String alertId) {
        return alertResponseMapper.toDetails(alertManagementUseCase.getAlert(alertId));
    }

    @GetMapping("/{alertId}/assistant-summary")
    public AnalystCaseSummaryResponse getAssistantSummary(@PathVariable String alertId) {
        return analystCaseSummaryUseCase.generateSummary(new AnalystCaseSummaryRequest(alertId, null, List.of(), java.util.Map.of()));
    }

    @PostMapping("/{alertId}/decision")
    public SubmitAnalystDecisionResponse submitDecision(
            @PathVariable String alertId,
            @Valid @RequestBody SubmitAnalystDecisionRequest request
    ) {
        return alertManagementUseCase.submitDecision(alertId, request);
    }
}
