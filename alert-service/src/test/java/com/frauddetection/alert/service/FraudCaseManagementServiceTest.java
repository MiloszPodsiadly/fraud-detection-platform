package com.frauddetection.alert.service;

import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditMutationRecorder;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class FraudCaseManagementServiceTest {

    @Test
    void shouldCreateRapidTransferCaseWithGroupedTransactionDetails() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = new FraudCaseManagementService(fraudCaseRepository, scoredTransactionRepository, new AuditMutationRecorder(auditService), analystActorResolver, metrics);

        var previousTransaction = scoredTransaction("rapid-txn-1", "rapid-customer-1", new BigDecimal("10000.00"));
        var currentEvent = TransactionFixtures.scoredTransaction()
                .withTransactionId("rapid-txn-2")
                .withCustomerId("rapid-customer-1")
                .withAmount(new BigDecimal("10000.00"), "PLN")
                .withRiskLevel(RiskLevel.CRITICAL)
                .withFeatureSnapshot(Map.of(
                        "rapidTransferFraudCaseCandidate", true,
                        "rapidTransferTransactionIds", List.of("rapid-txn-1", "rapid-txn-2"),
                        "rapidTransferTotalPln", new BigDecimal("20000.00"),
                        "rapidTransferThresholdPln", new BigDecimal("20000.00"),
                        "rapidTransferWindow", "PT1M",
                        "currentTransactionAmountPln", new BigDecimal("10000.00")
                ))
                .build();

        when(fraudCaseRepository.findByCaseKey("rapid-customer-1:RAPID_TRANSFER_BURST_20K_PLN:rapid-txn-1"))
                .thenReturn(Optional.empty());
        when(scoredTransactionRepository.findAllById(List.of("rapid-txn-1", "rapid-txn-2")))
                .thenReturn(List.of(previousTransaction));

        service.handleScoredTransaction(currentEvent);

        ArgumentCaptor<FraudCaseDocument> captor = ArgumentCaptor.forClass(FraudCaseDocument.class);
        verify(fraudCaseRepository).save(captor.capture());

        FraudCaseDocument savedCase = captor.getValue();
        assertThat(savedCase.getStatus()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(savedCase.getSuspicionType()).isEqualTo("RAPID_TRANSFER_BURST_20K_PLN");
        assertThat(savedCase.getTransactionIds()).containsExactly("rapid-txn-1", "rapid-txn-2");
        assertThat(savedCase.getTotalAmountPln()).isEqualByComparingTo("20000.00");
        assertThat(savedCase.getFirstTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:10:28Z"));
        assertThat(savedCase.getLastTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:15:28Z"));
        assertThat(savedCase.getTransactions())
                .extracting("transactionId")
                .containsExactly("rapid-txn-1", "rapid-txn-2");
        assertThat(savedCase.getTransactions())
                .extracting("amountPln")
                .containsExactly(new BigDecimal("10000.00"), new BigDecimal("10000.00"));
    }

    @Test
    void shouldBackfillMissingGroupedTransactionsWhenCaseIsRead() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = new FraudCaseManagementService(fraudCaseRepository, scoredTransactionRepository, new AuditMutationRecorder(auditService), analystActorResolver, metrics);

        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setCaseKey("rapid-customer-1:RAPID_TRANSFER_BURST_20K_PLN:rapid-txn-1");
        storedCase.setCustomerId("rapid-customer-1");
        storedCase.setSuspicionType("RAPID_TRANSFER_BURST_20K_PLN");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactionIds(List.of("rapid-txn-1", "rapid-txn-2", "rapid-txn-3"));
        storedCase.setTransactions(List.of(scoredCaseTransaction("rapid-txn-3", new BigDecimal("6800.00"))));
        storedCase.setFirstTransactionAt(Instant.parse("2026-04-20T10:15:28Z"));
        storedCase.setLastTransactionAt(Instant.parse("2026-04-20T10:15:28Z"));

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(scoredTransactionRepository.findAllById(storedCase.getTransactionIds())).thenReturn(List.of(
                scoredTransaction("rapid-txn-1", "rapid-customer-1", new BigDecimal("7400.00"), Instant.parse("2026-04-20T10:10:28Z")),
                scoredTransaction("rapid-txn-2", "rapid-customer-1", new BigDecimal("8600.00"), Instant.parse("2026-04-20T10:12:28Z")),
                scoredTransaction("rapid-txn-3", "rapid-customer-1", new BigDecimal("6800.00"), Instant.parse("2026-04-20T10:15:28Z"))
        ));
        when(fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FraudCaseDocument hydrated = service.getCase("case-1");

        assertThat(hydrated.getTransactions())
                .extracting("transactionId")
                .containsExactly("rapid-txn-1", "rapid-txn-2", "rapid-txn-3");
        assertThat(hydrated.getFirstTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:10:28Z"));
        assertThat(hydrated.getLastTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:15:28Z"));
        verify(fraudCaseRepository).save(hydrated);
    }

    @Test
    void shouldAuditFraudCaseUpdate() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = new FraudCaseManagementService(fraudCaseRepository, scoredTransactionRepository, new AuditMutationRecorder(auditService), analystActorResolver, metrics);

        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setCaseKey("rapid-customer-1:RAPID_TRANSFER_BURST_20K_PLN:rapid-txn-1");
        storedCase.setCustomerId("rapid-customer-1");
        storedCase.setSuspicionType("RAPID_TRANSFER_BURST_20K_PLN");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactionIds(List.of("rapid-txn-1"));
        storedCase.setTransactions(List.of(scoredCaseTransaction("rapid-txn-1", new BigDecimal("10000.00"))));

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(analystActorResolver.resolveActorId(eq("analyst-9"), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");

        FraudCaseDocument updated = service.updateCase("case-1", new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-9",
                "Confirmed after review",
                List.of("manual-review")
        ));

        assertThat(updated.getStatus()).isEqualTo(FraudCaseStatus.CONFIRMED_FRAUD);
        assertThat(updated.getAnalystId()).isEqualTo("principal-9");
        verify(auditService).audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-rapid-txn-1",
                "principal-9",
                AuditOutcome.ATTEMPTED,
                null
        );
        verify(auditService).audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-rapid-txn-1",
                "principal-9",
                AuditOutcome.SUCCESS,
                null
        );
        verify(metrics).recordFraudCaseUpdated();
    }

    @Test
    void shouldBlockFraudCaseUpdateWhenDurableAuditFails() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = new FraudCaseManagementService(fraudCaseRepository, scoredTransactionRepository, new AuditMutationRecorder(auditService), analystActorResolver, metrics);

        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactions(List.of(scoredCaseTransaction("rapid-txn-1", new BigDecimal("10000.00"))));

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(analystActorResolver.resolveActorId(eq("analyst-9"), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
        doThrow(new AuditPersistenceUnavailableException()).when(auditService).audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-rapid-txn-1",
                "principal-9",
                AuditOutcome.ATTEMPTED,
                null
        );

        assertThatThrownBy(() -> service.updateCase("case-1", new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-9",
                "Confirmed after review",
                List.of("manual-review")
        ))).isInstanceOf(AuditPersistenceUnavailableException.class);

        verify(fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
        verify(metrics, never()).recordFraudCaseUpdated();
        assertThat(storedCase.getStatus()).isEqualTo(FraudCaseStatus.OPEN);
    }

    private ScoredTransactionDocument scoredTransaction(String transactionId, String customerId, BigDecimal amount) {
        return scoredTransaction(transactionId, customerId, amount, Instant.parse("2026-04-20T10:10:28Z"));
    }

    private ScoredTransactionDocument scoredTransaction(String transactionId, String customerId, BigDecimal amount, Instant transactionTimestamp) {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(transactionId);
        document.setCustomerId(customerId);
        document.setCorrelationId("corr-" + transactionId);
        document.setTransactionTimestamp(transactionTimestamp);
        document.setTransactionAmount(new Money(amount, "PLN"));
        document.setFraudScore(0.42d);
        document.setRiskLevel(RiskLevel.LOW);
        return document;
    }

    private com.frauddetection.alert.persistence.FraudCaseTransactionDocument scoredCaseTransaction(String transactionId, BigDecimal amountPln) {
        com.frauddetection.alert.persistence.FraudCaseTransactionDocument document = new com.frauddetection.alert.persistence.FraudCaseTransactionDocument();
        document.setTransactionId(transactionId);
        document.setCorrelationId("corr-" + transactionId);
        document.setTransactionTimestamp(Instant.parse("2026-04-20T10:15:28Z"));
        document.setAmountPln(amountPln);
        document.setFraudScore(0.94d);
        document.setRiskLevel(RiskLevel.CRITICAL);
        return document;
    }
}
