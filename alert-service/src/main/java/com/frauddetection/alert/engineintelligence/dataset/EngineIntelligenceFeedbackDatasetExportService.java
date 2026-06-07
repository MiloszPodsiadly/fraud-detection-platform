package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class EngineIntelligenceFeedbackDatasetExportService {

    private final EngineIntelligenceFeedbackDatasetQueryRepository feedbackQueryRepository;
    private final AlertRepository alertRepository;
    private final EngineIntelligenceProjectionRepository projectionRepository;
    private final EngineIntelligenceFeedbackDatasetRecordMapper mapper;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceFeedbackDatasetExportService(
            EngineIntelligenceFeedbackDatasetQueryRepository feedbackQueryRepository,
            AlertRepository alertRepository,
            EngineIntelligenceProjectionRepository projectionRepository
    ) {
        this(
                feedbackQueryRepository,
                alertRepository,
                projectionRepository,
                new EngineIntelligenceFeedbackDatasetRecordMapper(),
                Clock.systemUTC()
        );
    }

    EngineIntelligenceFeedbackDatasetExportService(
            EngineIntelligenceFeedbackDatasetQueryRepository feedbackQueryRepository,
            AlertRepository alertRepository,
            EngineIntelligenceProjectionRepository projectionRepository,
            EngineIntelligenceFeedbackDatasetRecordMapper mapper,
            Clock clock
    ) {
        this.feedbackQueryRepository = Objects.requireNonNull(feedbackQueryRepository, "feedbackQueryRepository is required");
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository is required");
        this.projectionRepository = Objects.requireNonNull(projectionRepository, "projectionRepository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public EngineIntelligenceFeedbackDatasetExportResult export(EngineIntelligenceFeedbackDatasetExportRequest request) {
        Instant exportedAt = Instant.now(clock);
        List<EngineIntelligenceFeedbackDocument> rawRows;
        try {
            rawRows = feedbackQueryRepository.findBoundedBySubmittedAt(
                    request.fromInclusive(),
                    request.toInclusive(),
                    request.maxRecords()
            );
        } catch (RuntimeException exception) {
            return EngineIntelligenceFeedbackDatasetExportResult.failed(
                    request,
                    exportedAt,
                    EngineIntelligenceFeedbackDatasetExportFailureReason.FEEDBACK_STORE_UNAVAILABLE
            );
        }

        boolean truncated = rawRows.size() > request.maxRecords();
        Map<String, EngineIntelligenceFeedbackDatasetRecord> deduplicated = new LinkedHashMap<>();
        for (EngineIntelligenceFeedbackDocument feedback : rawRows) {
            EngineIntelligenceFeedbackDatasetRecord record;
            try {
                record = mapRecord(feedback);
            } catch (EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException exception) {
                return EngineIntelligenceFeedbackDatasetExportResult.failed(request, exportedAt, exception.reason());
            }
            deduplicated.putIfAbsent(record.transactionReference(), record);
        }
        List<EngineIntelligenceFeedbackDatasetRecord> records = new ArrayList<>();
        for (EngineIntelligenceFeedbackDatasetRecord record : deduplicated.values()) {
            if (records.size() == request.maxRecords()) {
                break;
            }
            records.add(record);
        }
        return EngineIntelligenceFeedbackDatasetExportResult.succeeded(
                request,
                exportedAt,
                rawRows.size(),
                truncated,
                records
        );
    }

    private EngineIntelligenceFeedbackDatasetRecord mapRecord(EngineIntelligenceFeedbackDocument feedback) {
        AlertDocument alert = alert(feedback)
                .orElseGet(() -> missingDecisionAlert(feedback));
        EngineIntelligenceProjection projection = projection(feedback).orElse(null);
        try {
            return mapper.map(feedback, alert, projection);
        } catch (EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK
            );
        }
    }

    private Optional<AlertDocument> alert(EngineIntelligenceFeedbackDocument feedback) {
        try {
            return alertRepository.findByTransactionId(feedback.getTransactionId());
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.ALERT_STORE_UNAVAILABLE
            );
        }
    }

    private Optional<EngineIntelligenceProjection> projection(EngineIntelligenceFeedbackDocument feedback) {
        try {
            return projectionRepository.findById(feedback.getTransactionId());
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.PROJECTION_STORE_UNAVAILABLE
            );
        }
    }

    private AlertDocument missingDecisionAlert(EngineIntelligenceFeedbackDocument feedback) {
        AlertDocument document = new AlertDocument();
        document.setTransactionId(feedback.getTransactionId());
        return document;
    }
}
