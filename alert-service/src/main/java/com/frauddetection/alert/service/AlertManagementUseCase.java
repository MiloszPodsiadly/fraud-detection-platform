package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AlertManagementUseCase {

    void handleScoredTransaction(TransactionScoredEvent event);

    List<AlertCase> listAlerts();

    Page<AlertCase> listAlerts(Pageable pageable);

    AlertCase getAlert(String alertId);

    SubmitAnalystDecisionResponse submitDecision(String alertId, SubmitAnalystDecisionRequest request);

    default SubmitAnalystDecisionResponse submitDecision(String alertId, SubmitAnalystDecisionRequest request, String idempotencyKey) {
        return submitDecision(alertId, request);
    }
}
