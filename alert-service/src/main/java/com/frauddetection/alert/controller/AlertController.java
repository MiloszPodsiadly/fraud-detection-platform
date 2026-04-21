package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.AlertDetailsResponse;
import com.frauddetection.alert.api.AlertSummaryResponse;
import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.service.AlertManagementUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertManagementUseCase alertManagementUseCase;
    private final AlertResponseMapper alertResponseMapper;

    public AlertController(AlertManagementUseCase alertManagementUseCase, AlertResponseMapper alertResponseMapper) {
        this.alertManagementUseCase = alertManagementUseCase;
        this.alertResponseMapper = alertResponseMapper;
    }

    @GetMapping
    public List<AlertSummaryResponse> listAlerts() {
        return alertManagementUseCase.listAlerts().stream().map(alertResponseMapper::toSummary).toList();
    }

    @GetMapping("/{alertId}")
    public AlertDetailsResponse getAlert(@PathVariable String alertId) {
        return alertResponseMapper.toDetails(alertManagementUseCase.getAlert(alertId));
    }

    @PostMapping("/{alertId}/decision")
    public SubmitAnalystDecisionResponse submitDecision(
            @PathVariable String alertId,
            @Valid @RequestBody SubmitAnalystDecisionRequest request
    ) {
        return alertManagementUseCase.submitDecision(alertId, request);
    }
}
