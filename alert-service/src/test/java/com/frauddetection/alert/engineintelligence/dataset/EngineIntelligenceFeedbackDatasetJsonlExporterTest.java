package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void serializationFailureAfterARecordReturnsOnlyFailureMetadata() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        int[] recordsSerialized = {0};
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            Map<?, ?> line = invocation.getArgument(0);
            if ("DATASET_RECORD".equals(line.get("type"))) {
                if (++recordsSerialized[0] == 1) {
                    return "{\"type\":\"DATASET_RECORD\",\"record\":\"sensitive-record-id\"}";
                }
                throw new IllegalStateException("raw serialization exception");
            }
            return "{\"type\":\"EXPORT_METADATA\",\"failureReason\":\""
                    + line.get("failureReason") + "\"}";
        });
        EngineIntelligenceFeedbackDatasetExportResult result = result(false, List.of(record(), record()));

        String jsonl = new EngineIntelligenceFeedbackDatasetJsonlExporter(objectMapper).exportJsonl(result);

        assertThat(jsonl.lines()).hasSize(1);
        assertThat(jsonl)
                .contains("\"type\":\"EXPORT_METADATA\"")
                .contains("\"failureReason\":\"SERIALIZATION_FAILED\"")
                .doesNotContain("DATASET_RECORD", "sensitive-record-id", "raw serialization exception");
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
                List.of(),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null
        );
    }
}
