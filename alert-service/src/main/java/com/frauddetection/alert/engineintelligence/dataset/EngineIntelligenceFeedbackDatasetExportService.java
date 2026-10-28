package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionException;
import org.springframework.data.mapping.MappingException;
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
        request = Objects.requireNonNull(request, "request is required");
        Instant exportedAt = Instant.now(clock);
        List<EngineIntelligenceFeedbackDocument> rawRows;
        try {
            rawRows = feedbackQueryRepository.findBoundedBySubmittedAt(
                    request.fromInclusive(),
                    request.toInclusive(),
                    request.maxRecords()
            );
        } catch (MappingException | ConversionException exception) {
            return EngineIntelligenceFeedbackDatasetExportResult.failed(
                    request,
                    exportedAt,
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK
            );
        } catch (RuntimeException exception) {
            return EngineIntelligenceFeedbackDatasetExportResult.failed(
                    request,
                    exportedAt,
                    EngineIntelligenceFeedbackDatasetExportFailureReason.FEEDBACK_STORE_UNAVAILABLE
            );
        }
        if (rawRows == null || rawRows.size() > request.maxRecords() + 1) {
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
            // Repository order is authoritative: submittedAt DESC, feedbackId ASC. The first row per transaction wins.
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
        mapper.validateFeedbackSource(feedback);
        AlertDocument alert = alert(feedback)
                .orElseGet(() -> missingDecisionAlert(feedback));
        EngineIntelligenceProjection projection = projection(feedback).orElse(null);
        return mapper.mapValidatedFeedback(feedback, alert, projection);
    }

    private Optional<AlertDocument> alert(EngineIntelligenceFeedbackDocument feedback) {
        try {
            Optional<AlertDocument> result = alertRepository.findByTransactionId(feedback.getTransactionId());
            if (result == null) {
                throw new IllegalStateException("alert repository returned null");
            }
            return result;
        } catch (MappingException | ConversionException exception) {
            throw new EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_ALERT_DATA
            );
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.ALERT_STORE_UNAVAILABLE
            );
        }
    }

    private Optional<EngineIntelligenceProjection> projection(EngineIntelligenceFeedbackDocument feedback) {
        try {
            Optional<EngineIntelligenceProjection> result = projectionRepository.findById(feedback.getTransactionId());
            if (result == null) {
                throw new IllegalStateException("projection repository returned null");
            }
            return result;
        } catch (MappingException | ConversionException exception) {
            throw new EngineIntelligenceFeedbackDatasetRecordMapper.CorruptedDatasetSourceException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_PROJECTION
            );
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
