package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFeedbackDatasetJsonlExporterTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant EXPORTED_AT = Instant.parse("2026-06-02T12:00:00Z");

    @Test
    void jsonlMetadataLineContainsTruncationMetadata() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(result(true, List.of()));

        assertThat(jsonl.lines().findFirst().orElseThrow())
                .contains("\"type\":\"EXPORT_METADATA\"")
                .contains("\"truncated\":true")
                .contains("\"rawRowsRead\":2")
                .contains("\"recordsReturned\":0")
                .contains("\"timeBasis\":\"FEEDBACK_SUBMITTED_AT\"");
    }

    @Test
    void oneDatasetRecordSerializesToOneLine() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(result(false, List.of(record())));

        assertThat(jsonl.lines()).hasSize(2);
        assertThat(jsonl.lines().skip(1).findFirst().orElseThrow())
                .contains("\"type\":\"DATASET_RECORD\"")
                .contains("\"evaluationLabel\":\"POSITIVE\"");
    }

    @Test
    void jsonlSerializationIsDeterministic() {
        EngineIntelligenceFeedbackDatasetJsonlExporter exporter = new EngineIntelligenceFeedbackDatasetJsonlExporter();
        EngineIntelligenceFeedbackDatasetExportResult result = result(false, List.of(record()));

        assertThat(exporter.exportJsonl(result)).isEqualTo(exporter.exportJsonl(result));
    }

    @Test
    void emptyExportSerializesSafely() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(result(false, List.of()));

        assertThat(jsonl.lines()).hasSize(1);
        assertThat(jsonl).contains("\"recordsReturned\":0");
    }

    @Test
    void jsonlDoesNotContainForbiddenFieldNames() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(result(false, List.of(record())));

        assertThat(jsonl)
                .doesNotContain(
                        "customerId",
                        "accountId",
                        "cardId",
                        "deviceId",
                        "merchantId",
                        "submittedBy",
                        "analystId",
                        "correlationId",
                        "idempotencyKey",
                        "requestPayloadHash",
                        "rawPayload",
                        "rawFeatureVector",
                        "rawEvidence",
                        "rawContribution",
                        "groundTruth",
                        "modelTrainingLabel",
                        "finalDecision",
                        "paymentAuthorization"
                );
    }

    private EngineIntelligenceFeedbackDatasetExportResult result(
            boolean truncated,
            List<EngineIntelligenceFeedbackDatasetRecord> records
    ) {
        return new EngineIntelligenceFeedbackDatasetExportResult(
                FROM,
                TO,
                EXPORTED_AT,
                10,
                truncated ? 2 : records.size(),
                records.size(),
                truncated,
                EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT,
                EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC,
                null,
                records
        );
    }

    private EngineIntelligenceFeedbackDatasetRecord record() {
        return new EngineIntelligenceFeedbackDatasetRecord(
                "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                FROM,
                EngineIntelligenceFeedbackDatasetLabel.POSITIVE,
                EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION,
                com.frauddetection.common.events.enums.AnalystDecision.CONFIRMED_FRAUD,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("ML_MODEL_SIGNAL"),
                List.of("ML_MODEL_SIGNAL")
        );
    }
}
