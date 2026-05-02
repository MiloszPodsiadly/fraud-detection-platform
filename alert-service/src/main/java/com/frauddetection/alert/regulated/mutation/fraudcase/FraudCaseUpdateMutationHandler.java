package com.frauddetection.alert.regulated.mutation.fraudcase;

import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.exception.AlertNotFoundException;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class FraudCaseUpdateMutationHandler {

    private final FraudCaseRepository fraudCaseRepository;
    private final AlertServiceMetrics metrics;

    public FraudCaseUpdateMutationHandler(FraudCaseRepository fraudCaseRepository, AlertServiceMetrics metrics) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.metrics = metrics;
    }

    public FraudCaseDocument update(String caseId, UpdateFraudCaseRequest request, String actorId) {
        FraudCaseDocument document = fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new AlertNotFoundException(caseId));
        Instant now = Instant.now();
        document.setStatus(request.status());
        document.setAnalystId(actorId);
        document.setDecisionReason(request.decisionReason());
        document.setDecisionTags(request.tags() == null ? List.of() : List.copyOf(request.tags()));
        document.setDecidedAt(now);
        document.setUpdatedAt(now);
        FraudCaseDocument saved = fraudCaseRepository.save(document);
        metrics.recordFraudCaseUpdated();
        return saved;
    }
}
