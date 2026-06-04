package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EngineIntelligenceFeedbackReadModelMapper {

    public EngineIntelligenceFeedbackReadModel map(
            String transactionId,
            List<EngineIntelligenceFeedbackDocument> documents,
            int limit,
            boolean hasMore
    ) {
        List<EngineIntelligenceFeedbackEntryReadModel> feedback = documents == null
                ? List.of()
                : documents.stream().map(this::mapEntry).toList();
        return new EngineIntelligenceFeedbackReadModel(
                transactionId,
                feedback,
                new EngineIntelligenceFeedbackPage(limit, hasMore)
        );
    }

    EngineIntelligenceFeedbackEntryReadModel mapEntry(EngineIntelligenceFeedbackDocument document) {
        return new EngineIntelligenceFeedbackEntryReadModel(
                document.getFeedbackId(),
                document.isEngineIntelligenceAvailable(),
                document.getFeedbackType(),
                document.getUsefulness(),
                document.getAccuracyAssessment(),
                document.getSelectedReasonCodes(),
                document.getSubmittedAt()
        );
    }
}
