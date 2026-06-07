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
                .contains("\"evaluationLabel\":\"ANALYST_CONFIRMED_FRAUD\"");
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
    void jsonlExporterDoesNotEmitRecordsForFailedExport() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(failedResult());

        assertThat(jsonl).doesNotContain("\"type\":\"DATASET_RECORD\"");
    }

    @Test
    void failedJsonlContainsOnlyMetadataLine() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(failedResult());

        assertThat(jsonl.lines()).hasSize(1);
        assertThat(jsonl.lines().findFirst().orElseThrow()).contains("\"type\":\"EXPORT_METADATA\"");
    }

    @Test
    void failedJsonlContainsFailureReason() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(failedResult());

        assertThat(jsonl).contains("\"failureReason\":\"CORRUPTED_FEEDBACK\"");
    }

    @Test
    void failedExportMustNotBeConsumedAsSuccessfulEmptyDataset() {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(failedResult());

        assertThat(jsonl)
                .contains("\"recordsReturned\":0")
                .contains("\"failureReason\":\"CORRUPTED_FEEDBACK\"");
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
                        "rawMlRequest",
                        "rawMlResponse",
                        "stacktrace",
                        "endpoint",
                        "token",
                        "secret",
                        "groundTruth",
                        "modelTrainingLabel",
                        "finalDecision",
                        "paymentAuthorization"
                );
    }

    @Test
    void jsonlDoesNotContainSubmittedBy() {
        assertJsonlDoesNotContain("submittedBy");
    }

    @Test
    void jsonlDoesNotContainAnalystId() {
        assertJsonlDoesNotContain("analystId");
    }

    @Test
    void jsonlDoesNotContainCorrelationId() {
        assertJsonlDoesNotContain("correlationId");
    }

    @Test
    void jsonlDoesNotContainIdempotencyKey() {
        assertJsonlDoesNotContain("idempotencyKey");
    }

    @Test
    void jsonlDoesNotContainRequestPayloadHash() {
        assertJsonlDoesNotContain("requestPayloadHash");
    }

    @Test
    void jsonlDoesNotContainCustomerId() {
        assertJsonlDoesNotContain("customerId");
    }

    @Test
    void jsonlDoesNotContainAccountId() {
        assertJsonlDoesNotContain("accountId");
    }

    @Test
    void jsonlDoesNotContainCardId() {
        assertJsonlDoesNotContain("cardId");
    }

    @Test
    void jsonlDoesNotContainDeviceId() {
        assertJsonlDoesNotContain("deviceId");
    }

    @Test
    void jsonlDoesNotContainMerchantId() {
        assertJsonlDoesNotContain("merchantId");
    }

    @Test
    void jsonlDoesNotContainRawPayload() {
        assertJsonlDoesNotContain("rawPayload");
    }

    @Test
    void jsonlDoesNotContainRawFeatureVector() {
        assertJsonlDoesNotContain("rawFeatureVector");
    }

    @Test
    void jsonlDoesNotContainRawEvidence() {
        assertJsonlDoesNotContain("rawEvidence");
    }

    @Test
    void jsonlDoesNotContainRawContribution() {
        assertJsonlDoesNotContain("rawContribution");
    }

    @Test
    void jsonlDoesNotContainRawMlRequest() {
        assertJsonlDoesNotContain("rawMlRequest");
    }

    @Test
    void jsonlDoesNotContainRawMlResponse() {
        assertJsonlDoesNotContain("rawMlResponse");
    }

    @Test
    void jsonlDoesNotContainStacktrace() {
        assertJsonlDoesNotContain("stacktrace");
    }

    @Test
    void jsonlDoesNotContainEndpoint() {
        assertJsonlDoesNotContain("endpoint");
    }

    @Test
    void jsonlDoesNotContainToken() {
        assertJsonlDoesNotContain("token");
    }

    @Test
    void jsonlDoesNotContainSecret() {
        assertJsonlDoesNotContain("secret");
    }

    @Test
    void jsonlDoesNotContainGroundTruth() {
        assertJsonlDoesNotContain("groundTruth");
    }

    @Test
    void jsonlDoesNotContainModelTrainingLabel() {
        assertJsonlDoesNotContain("modelTrainingLabel");
    }

    @Test
    void jsonlDoesNotContainFinalDecision() {
        assertJsonlDoesNotContain("finalDecision");
    }

    @Test
    void jsonlDoesNotContainPaymentAuthorization() {
        assertJsonlDoesNotContain("paymentAuthorization");
    }

    private void assertJsonlDoesNotContain(String forbiddenText) {
        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter().exportJsonl(result(false, List.of(record())));

        assertThat(jsonl).doesNotContain(forbiddenText);
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

    private EngineIntelligenceFeedbackDatasetExportResult failedResult() {
        return new EngineIntelligenceFeedbackDatasetExportResult(
                FROM,
                TO,
                EXPORTED_AT,
                10,
                0,
                0,
                false,
                EngineIntelligenceFeedbackDatasetTimeBasis.FEEDBACK_SUBMITTED_AT,
                EngineIntelligenceFeedbackDatasetDeduplicationPolicy.TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC,
                EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_FEEDBACK,
                List.of()
        );
    }

    private EngineIntelligenceFeedbackDatasetRecord record() {
        return new EngineIntelligenceFeedbackDatasetRecord(
                "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                FROM,
                EngineIntelligenceFeedbackDatasetLabel.ANALYST_CONFIRMED_FRAUD,
                EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION,
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
