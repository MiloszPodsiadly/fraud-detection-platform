package com.frauddetection.alert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.evidence.EvidenceSeverity;
import com.frauddetection.alert.evidence.EvidenceSnapshotItem;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.evidence.EvidenceType;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudCaseEvidenceSummaryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-22T10:00:00Z");

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @Mock
    private AlertRepository alertRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void FraudCaseEvidenceSummaryFromLinkedAlertsTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1", "alert-2")));
        when(alertRepository.findAllById(List.of("alert-1", "alert-2"))).thenReturn(List.of(
                alert("alert-1", evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.AVAILABLE, EvidenceSeverity.CRITICAL)),
                alert("alert-2", evidence("RAPID_TRANSFER_FRAUD_CASE", EvidenceStatus.AVAILABLE, EvidenceSeverity.HIGH))
        ));

        var response = service.summary("case-1");

        assertThat(response.caseId()).isEqualTo("case-1");
        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(response.linkedAlertCount()).isEqualTo(2);
        assertThat(response.evidenceItemCount()).isEqualTo(2);
        assertThat(response.topReasonCodes()).containsExactly("HIGH_AMOUNT_ACTIVITY", "RAPID_TRANSFER_FRAUD_CASE");
        assertThat(response.highestSeverityEvidence()).hasSize(2);
        assertThat(response.evidenceBySource()).hasSize(1);
        assertThat(response.evidenceByStatus()).hasSize(1);
        assertThat(response.partial()).isFalse();
        assertThat(response.legacy()).isFalse();
    }

    @Test
    void FraudCaseEvidenceSummaryEmptyLinkedAlertsTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert("alert-1")));

        var response = service.summary("case-1");

        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(response.legacy()).isFalse();
        assertThat(response.evidenceItemCount()).isZero();
    }

    @Test
    void FraudCaseEvidenceSummaryLegacyCaseTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1")));

        var response = service.summary("case-1");

        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.LEGACY);
        assertThat(response.legacy()).isTrue();
        assertThat(response.linkedAlertCount()).isZero();
    }

    @Test
    void FraudCaseEvidenceStatusAggregationTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert(
                "alert-1",
                evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.AVAILABLE, EvidenceSeverity.LOW),
                evidence("RAPID_TRANSFER_FRAUD_CASE", EvidenceStatus.PARTIAL, EvidenceSeverity.HIGH)
        )));

        var response = service.summary("case-1");

        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(response.partial()).isTrue();
        assertThat(response.evidenceByStatus()).extracting("status")
                .containsExactly(EvidenceStatus.AVAILABLE, EvidenceStatus.PARTIAL);
    }

    @Test
    void FraudCaseEvidenceSummaryNoEvidenceDoesNotClaimAvailableTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert("alert-1")));

        assertThat(service.summary("case-1").aggregateEvidenceStatus()).isNotEqualTo(EvidenceStatus.AVAILABLE);
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotOverclaimAvailableWhenAnySourcePartialTest() {
        assertAggregateForStatus(EvidenceStatus.PARTIAL, EvidenceStatus.PARTIAL);
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotOverclaimAvailableWhenAnySourceUnavailableTest() {
        assertAggregateForStatus(EvidenceStatus.UNAVAILABLE, EvidenceStatus.PARTIAL);
    }

    @Test
    void FraudCaseEvidenceSummaryErrorDominatesTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert(
                "alert-1",
                evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.PARTIAL, EvidenceSeverity.LOW),
                evidence("RAPID_TRANSFER_FRAUD_CASE", EvidenceStatus.ERROR, EvidenceSeverity.MEDIUM)
        )));

        assertThat(service.summary("case-1").aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.ERROR);
    }

    @Test
    void FraudCaseEvidenceSummaryLinkedAlertLimitTest() {
        FraudCaseEvidenceSummaryService service = service();
        List<String> alertIds = IntStream.rangeClosed(1, 101).mapToObj(index -> "alert-" + index).toList();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", alertIds.toArray(String[]::new))));
        List<AlertDocument> firstHundred = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> alert("alert-" + index, evidence("REASON_" + index, EvidenceStatus.AVAILABLE, EvidenceSeverity.LOW)))
                .toList();
        when(alertRepository.findAllById(alertIds.subList(0, 100))).thenReturn(firstHundred);

        var response = service.summary("case-1");

        assertThat(response.linkedAlertCount()).isEqualTo(101);
        assertThat(response.evidenceItemCount()).isEqualTo(100);
        assertThat(response.truncated()).isTrue();
        assertThat(response.partial()).isTrue();
        assertThat(response.truncationReason()).isEqualTo("LINKED_ALERT_LIMIT_EXCEEDED");
    }

    @Test
    void FraudCaseEvidenceSummaryReasonCodesAreDerivedNotInventedTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert(
                "alert-1",
                evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.AVAILABLE, EvidenceSeverity.HIGH),
                evidence("UNKNOWN", EvidenceStatus.PARTIAL, EvidenceSeverity.LOW),
                evidence(null, EvidenceStatus.PARTIAL, EvidenceSeverity.LOW)
        )));

        assertThat(service.summary("case-1").topReasonCodes()).containsExactly("HIGH_AMOUNT_ACTIVITY");
    }

    @Test
    void FraudCaseEvidenceSummaryGeneratedAtIsCurrentReadTimeNotOutcomeTimeTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert(
                "alert-1",
                evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.AVAILABLE, EvidenceSeverity.HIGH)
        )));

        assertThat(service.summary("case-1").generatedAt()).isEqualTo(NOW);
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotImplyVerdictTest() throws Exception {
        String json = jsonResponseWithSensitiveSourceFields();

        assertThat(json)
                .doesNotContain("verdict")
                .doesNotContain("fraudConfirmed")
                .doesNotContain("caseDecision")
                .doesNotContain("analystDecision")
                .doesNotContain("finalOutcome");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotUseFraudProofWordingTest() throws Exception {
        String json = jsonResponseWithSensitiveSourceFields();

        assertThat(json)
                .doesNotContain("proofOfFraud")
                .doesNotContain("legalProof")
                .doesNotContain("confirmed fraud");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeRawEvidenceAttributesTest() throws Exception {
        assertThat(jsonResponseWithSensitiveSourceFields()).doesNotContain("raw-sensitive-attribute");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeRawModelPayloadTest() throws Exception {
        assertThat(jsonResponseWithSensitiveSourceFields()).doesNotContain("scoreDetails").doesNotContain("raw-model-payload");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeFeatureSnapshotTest() throws Exception {
        assertThat(jsonResponseWithSensitiveSourceFields()).doesNotContain("featureSnapshot");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeRawEventPayloadTest() throws Exception {
        assertThat(jsonResponseWithSensitiveSourceFields()).doesNotContain("source-event-1").doesNotContain("correlation-1");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeCustomerAccountIdentifiersInItemsTest() throws Exception {
        assertThat(jsonResponseWithSensitiveSourceFields()).doesNotContain("customer-1").doesNotContain("account-1");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeTransactionIdentifiersInEvidenceItemsTest() throws Exception {
        assertThat(jsonResponseWithSensitiveSourceFields()).doesNotContain("txn-1");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeRawIdentifiersInTitleTest() throws Exception {
        String json = jsonResponseWithDangerousTitleDescription();

        assertThat(json)
                .doesNotContain("customer-1")
                .doesNotContain("account-1")
                .doesNotContain("txn-1")
                .doesNotContain("correlation-1")
                .doesNotContain("alert-secret")
                .doesNotContain("source-event-1");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeRawIdentifiersInDescriptionTest() throws Exception {
        String json = jsonResponseWithDangerousTitleDescription();

        assertThat(json)
                .doesNotContain("customer-1")
                .doesNotContain("account-1")
                .doesNotContain("txn-1")
                .doesNotContain("correlation-1")
                .doesNotContain("source-event-1");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeRawModelPayloadInTitleOrDescriptionTest() throws Exception {
        assertThat(jsonResponseWithDangerousTitleDescription())
                .doesNotContain("raw-model-payload")
                .doesNotContain("raw-baseline")
                .doesNotContain("scoreDetails")
                .doesNotContain("featureSnapshot");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotExposeRawEventPayloadInTitleOrDescriptionTest() throws Exception {
        assertThat(jsonResponseWithDangerousTitleDescription())
                .doesNotContain("payload")
                .doesNotContain("secret:true")
                .doesNotContain("{secret:true}");
    }

    @Test
    void FraudCaseEvidenceSummaryTitleDescriptionAreBoundedProductCopyTest() {
        FraudCaseEvidenceSummaryService service = service();
        EvidenceSnapshotItem source = dangerousTextEvidence();
        when(fraudCaseRepository.findById("case-sensitive")).thenReturn(Optional.of(caseWithAlerts("case-sensitive", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert("alert-1", source)));

        var item = service.summary("case-sensitive").highestSeverityEvidence().getFirst();

        assertThat(item.title()).isNotBlank();
        assertThat(item.description()).isNotBlank();
        assertThat(item.title()).isNotEqualTo(source.title());
        assertThat(item.description()).isNotEqualTo(source.description());
        assertThat(item.title()).isEqualTo("Rule evidence");
        assertThat(item.description()).isEqualTo("Bounded evidence metadata derived from the linked alert evidence snapshot.");
    }

    @Test
    void FraudCaseEvidenceSummaryMissingLinkedAlertDoesNotOverclaimAvailableTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1", "alert-2")));
        when(alertRepository.findAllById(List.of("alert-1", "alert-2"))).thenReturn(List.of(
                alert("alert-1", evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.AVAILABLE, EvidenceSeverity.HIGH))
        ));

        var response = service.summary("case-1");

        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(response.partial()).isTrue();
        assertThat(response.linkedAlertCount()).isEqualTo(2);
        assertThat(response.evidenceItemCount()).isEqualTo(1);
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void FraudCaseEvidenceSummaryTruncatedDoesNotOverclaimAvailableTest() {
        FraudCaseEvidenceSummaryService service = service();
        List<String> alertIds = IntStream.rangeClosed(1, 101).mapToObj(index -> "alert-" + index).toList();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", alertIds.toArray(String[]::new))));
        List<AlertDocument> firstHundred = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> alert("alert-" + index, evidence("REASON_" + index, EvidenceStatus.AVAILABLE, EvidenceSeverity.LOW)))
                .toList();
        when(alertRepository.findAllById(alertIds.subList(0, 100))).thenReturn(firstHundred);

        var response = service.summary("case-1");

        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(response.partial()).isTrue();
        assertThat(response.truncated()).isTrue();
        assertThat(response.truncationReason()).isEqualTo("LINKED_ALERT_LIMIT_EXCEEDED");
        assertThat(response.linkedAlertCount()).isEqualTo(101);
        assertThat(response.evidenceItemCount()).isEqualTo(100);
    }

    @Test
    void FraudCaseEvidenceSummaryErrorStillDominatesWhenMissingLinkedAlertTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1", "alert-2")));
        when(alertRepository.findAllById(List.of("alert-1", "alert-2"))).thenReturn(List.of(
                alert("alert-1", evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.ERROR, EvidenceSeverity.HIGH))
        ));

        var response = service.summary("case-1");

        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.ERROR);
        assertThat(response.partial()).isTrue();
    }

    @Test
    void FraudCaseEvidenceSummaryNullEvidenceSnapshotDoesNotFailTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alertWithoutEvidenceSnapshot("alert-1")));

        var response = service.summary("case-1");

        assertThat(response.aggregateEvidenceStatus()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(response.evidenceItemCount()).isZero();
        assertThat(response.partial()).isFalse();
        assertThat(response.legacy()).isFalse();
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotReuseFullAlertEvidenceSnapshotDtoTest() {
        assertThat(com.frauddetection.alert.api.EvidenceSummaryItemResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("evidenceId", "sourceEventId", "transactionId", "correlationId", "attributes",
                        "value", "baselineValue", "scoreDetails", "featureSnapshot");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotReuseFullAlertDetailsDtoTest() {
        assertThat(com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("alertId", "alerts", "customerId", "transactionId", "fraudScore",
                        "decision", "decisionReason", "decisionTags");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotMutateCaseLifecycleTest() {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert("alert-1")));

        service.summary("case-1");

        verify(fraudCaseRepository, never()).save(any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotCreateCaseTimelineEventTest() {
        assertThat(FraudCaseEvidenceSummaryService.class.getDeclaredFields())
                .extracting(field -> field.getType().getSimpleName())
                .doesNotContain("FraudCaseAuditRepository");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotPublishKafkaEventTest() {
        assertThat(FraudCaseEvidenceSummaryService.class.getDeclaredFields())
                .extracting(field -> field.getType().getSimpleName())
                .doesNotContain("KafkaTemplate", "StreamBridge");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotCreateOrEditEvidenceTest() {
        assertThat(FraudCaseEvidenceSummaryService.class.getDeclaredFields())
                .extracting(field -> field.getType().getSimpleName())
                .doesNotContain("EvidenceRepository", "EvidenceProjectionService");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotCreateAnalystDecisionTest() {
        assertThat(FraudCaseEvidenceSummaryService.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("addDecision", "createDecision", "decide");
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotSetFinalOutcomeTest() {
        assertThat(FraudCaseEvidenceSummaryService.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("closeCase", "setFinalOutcome", "confirmFraud");
    }

    private void assertAggregateForStatus(EvidenceStatus inputStatus, EvidenceStatus expectedStatus) {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(caseWithAlerts("case-1", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert(
                "alert-1",
                evidence("HIGH_AMOUNT_ACTIVITY", EvidenceStatus.AVAILABLE, EvidenceSeverity.LOW),
                evidence("RAPID_TRANSFER_FRAUD_CASE", inputStatus, EvidenceSeverity.HIGH)
        )));

        assertThat(service.summary("case-1").aggregateEvidenceStatus()).isEqualTo(expectedStatus);
    }

    private String jsonResponseWithSensitiveSourceFields() throws Exception {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-sensitive")).thenReturn(Optional.of(caseWithAlerts("case-sensitive", "alert-1")));
        AlertDocument alert = alert("alert-1", fullEvidence());
        alert.setCustomerId("customer-1");
        alert.setTransactionId("txn-alert-1");
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert));

        return objectMapper.writeValueAsString(service.summary("case-sensitive"));
    }

    private String jsonResponseWithDangerousTitleDescription() throws Exception {
        FraudCaseEvidenceSummaryService service = service();
        when(fraudCaseRepository.findById("case-sensitive")).thenReturn(Optional.of(caseWithAlerts("case-sensitive", "alert-1")));
        when(alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert("alert-1", dangerousTextEvidence())));

        return objectMapper.writeValueAsString(service.summary("case-sensitive"));
    }

    private FraudCaseEvidenceSummaryService service() {
        return new FraudCaseEvidenceSummaryService(
                fraudCaseRepository,
                alertRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private FraudCaseDocument caseWithAlerts(String caseId, String... alertIds) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(caseId);
        document.setLinkedAlertIds(List.of(alertIds));
        return document;
    }

    private AlertDocument alert(String alertId, EvidenceSnapshotItem... evidence) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setEvidenceSnapshot(List.of(evidence));
        return document;
    }

    private AlertDocument alertWithoutEvidenceSnapshot(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        return document;
    }

    private EvidenceSnapshotItem evidence(String reasonCode, EvidenceStatus status, EvidenceSeverity severity) {
        return new EvidenceSnapshotItem(
                reasonCode,
                EvidenceType.RULE_MATCH,
                severity,
                EvidenceSource.ALERT_SERVICE,
                status,
                "Evidence title",
                "Evidence description",
                "value",
                "baseline",
                Instant.parse("2026-05-21T10:00:00Z")
        );
    }

    private EvidenceSnapshotItem fullEvidence() {
        return new EvidenceSnapshotItem(
                "evidence-1",
                "source-event-1",
                "txn-1",
                "correlation-1",
                "HIGH_AMOUNT_ACTIVITY",
                EvidenceType.RULE_MATCH,
                EvidenceSource.ALERT_SERVICE,
                EvidenceStatus.AVAILABLE,
                EvidenceSeverity.CRITICAL,
                "Evidence title",
                "Evidence description",
                "raw-model-payload",
                "account-1",
                java.util.Map.of("safeSignal", "raw-sensitive-attribute"),
                Instant.parse("2026-05-21T10:00:00Z"),
                Instant.parse("2026-05-21T10:01:00Z"),
                "strategy",
                "model",
                "version",
                Instant.parse("2026-05-21T10:02:00Z")
        );
    }

    private EvidenceSnapshotItem dangerousTextEvidence() {
        return new EvidenceSnapshotItem(
                "evidence-1",
                "source-event-1",
                "txn-1",
                "correlation-1",
                "HIGH_AMOUNT_ACTIVITY",
                EvidenceType.RULE_MATCH,
                EvidenceSource.ALERT_SERVICE,
                EvidenceStatus.AVAILABLE,
                EvidenceSeverity.CRITICAL,
                "customer-1 account-1 txn-1 correlation-1 alert-secret raw-model-payload scoreDetails featureSnapshot",
                "source-event-1 customer customer-1 account account-1 transaction txn-1 correlation correlation-1 model payload {secret:true}",
                "raw-model-payload",
                "raw-baseline",
                Map.of("safeSignal", "customer-1 account-1"),
                Instant.parse("2026-05-21T10:00:00Z"),
                Instant.parse("2026-05-21T10:01:00Z"),
                "strategy",
                "model",
                "version",
                Instant.parse("2026-05-21T10:02:00Z")
        );
    }
}
