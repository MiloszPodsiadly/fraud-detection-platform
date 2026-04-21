package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;

import java.util.List;

public interface AlertManagementUseCase {

    void handleScoredTransaction(TransactionScoredEvent event);

    List<AlertCase> listAlerts();

    AlertCase getAlert(String alertId);

    SubmitAnalystDecisionResponse submitDecision(String alertId, SubmitAnalystDecisionRequest request);
}
