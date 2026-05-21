package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.CustomerContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspiciousTransactionLinkedAlertContextServiceTest {

    private final SuspiciousTransactionRepository suspiciousTransactionRepository = mock(SuspiciousTransactionRepository.class);
    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final SuspiciousTransactionLinkedAlertContextService service =
            new SuspiciousTransactionLinkedAlertContextService(suspiciousTransactionRepository, alertRepository);

    @Test
    void missingSuspiciousTransactionDoesNotReturnNoLinkedAlertAndDoesNotLookupAlert() {
        when(suspiciousTransactionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLinkedAlertContext("missing"))
                .isInstanceOf(SuspiciousTransactionLinkedAlertContextNotFoundException.class);

        verify(alertRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blankSuspiciousTransactionIdDoesNotLookupAlert() {
        assertThatThrownBy(() -> service.resolveLinkedAlertContext(" "))
                .isInstanceOf(SuspiciousTransactionLinkedAlertContextNotFoundException.class);

        verify(suspiciousTransactionRepository, never()).findById(org.mockito.ArgumentMatchers.any());
        verify(alertRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noLinkedAlertDoesNotLookupAlertRepository() {
        SuspiciousTransactionDocument suspiciousTransaction = suspiciousTransaction("suspicious-1", null);
        when(suspiciousTransactionRepository.findById("suspicious-1")).thenReturn(Optional.of(suspiciousTransaction));

        AlertLinkedContextResponse response = service.resolveLinkedAlertContext("suspicious-1");

        assertThat(response.state()).isEqualTo(LinkedAlertContextState.NO_LINKED_ALERT);
        verify(alertRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingAlertReturnsNotFoundWithoutAlertFields() {
        SuspiciousTransactionDocument suspiciousTransaction = suspiciousTransaction("suspicious-1", "alert-1");
        when(suspiciousTransactionRepository.findById("suspicious-1")).thenReturn(Optional.of(suspiciousTransaction));
        when(alertRepository.findById("alert-1")).thenReturn(Optional.empty());

        AlertLinkedContextResponse response = service.resolveLinkedAlertContext("suspicious-1");

        assertThat(response.state()).isEqualTo(LinkedAlertContextState.LINKED_ALERT_NOT_FOUND);
        assertThat(response.alertId()).isNull();
        assertThat(response.transactionId()).isNull();
        assertThat(response.reasonCodes()).isEmpty();
    }

    @Test
    void linkedAlertIdMismatchFailsClosedWithoutAlertFields() {
        SuspiciousTransactionDocument suspiciousTransaction = suspiciousTransaction("suspicious-1", "alert-1");
        AlertDocument alert = alert("alert-other");
        when(suspiciousTransactionRepository.findById("suspicious-1")).thenReturn(Optional.of(suspiciousTransaction));
        when(alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));

        AlertLinkedContextResponse response = service.resolveLinkedAlertContext("suspicious-1");

        assertThat(response.state()).isEqualTo(LinkedAlertContextState.LINKED_ALERT_RELATIONSHIP_MISMATCH);
        assertThat(response.alertId()).isNull();
        assertThat(response.customerId()).isNull();
    }

    @Test
    void transactionMismatchFailsClosedWithoutExpectedOrActualValues() {
        SuspiciousTransactionDocument suspiciousTransaction = suspiciousTransaction("suspicious-1", "alert-1");
        AlertDocument alert = alert("alert-1");
        alert.setTransactionId("different-transaction");
        when(suspiciousTransactionRepository.findById("suspicious-1")).thenReturn(Optional.of(suspiciousTransaction));
        when(alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));

        AlertLinkedContextResponse response = service.resolveLinkedAlertContext("suspicious-1");

        assertThat(response.state()).isEqualTo(LinkedAlertContextState.LINKED_ALERT_RELATIONSHIP_MISMATCH);
        assertThat(response.transactionId()).isNull();
    }

    @Test
    void optionalBlankCompatibilityFieldsDoNotFailClosed() {
        SuspiciousTransactionDocument suspiciousTransaction = suspiciousTransaction("suspicious-1", "alert-1");
        suspiciousTransaction.setCustomerId(null);
        AlertDocument alert = alert("alert-1");
        alert.setCustomerId("customer-1");
        when(suspiciousTransactionRepository.findById("suspicious-1")).thenReturn(Optional.of(suspiciousTransaction));
        when(alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));

        AlertLinkedContextResponse response = service.resolveLinkedAlertContext("suspicious-1");

        assertThat(response.state()).isEqualTo(LinkedAlertContextState.LINKED_ALERT_AVAILABLE);
    }

    @Test
    void availableResponseMapsOnlyMinimalReadContext() {
        SuspiciousTransactionDocument suspiciousTransaction = suspiciousTransaction("suspicious-1", "alert-1");
        when(suspiciousTransactionRepository.findById("suspicious-1")).thenReturn(Optional.of(suspiciousTransaction));
        when(alertRepository.findById("alert-1")).thenReturn(Optional.of(alert("alert-1")));

        AlertLinkedContextResponse response = service.resolveLinkedAlertContext("suspicious-1");

        assertThat(response.state()).isEqualTo(LinkedAlertContextState.LINKED_ALERT_AVAILABLE);
        assertThat(response.alertId()).isEqualTo("alert-1");
        assertThat(response.transactionId()).isEqualTo("transaction-1");
        assertThat(response.customerId()).isEqualTo("customer-1");
        assertThat(response.accountId()).isEqualTo("account-1");
        assertThat(response.alertScore()).isEqualTo(0.94);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(response.alertStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(response.reasonCodes()).containsExactly("HIGH_AMOUNT", "RAPID_TRANSFER");
        assertThat(response.correlationId()).isEqualTo("correlation-1");
        assertThat(response.scoreDecisionId()).isEqualTo("score-decision-1");
    }

    private SuspiciousTransactionDocument suspiciousTransaction(String id, String linkedAlertId) {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();
        document.setSuspiciousTransactionId(id);
        document.setLinkedAlertId(linkedAlertId);
        document.setTransactionId("transaction-1");
        document.setCustomerId("customer-1");
        document.setAccountId("account-1");
        document.setCorrelationId("correlation-1");
        document.setScoreDecisionId("score-decision-1");
        return document;
    }

    private AlertDocument alert(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setTransactionId("transaction-1");
        document.setCustomerId("customer-1");
        document.setCorrelationId("correlation-1");
        document.setCreatedAt(Instant.parse("2026-05-20T10:00:00Z"));
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setFraudScore(0.94);
        document.setAlertStatus(AlertStatus.OPEN);
        document.setReasonCodes(List.of("HIGH_AMOUNT", "RAPID_TRANSFER"));
        document.setCustomerContext(new CustomerContext(
                "customer-1",
                "account-1",
                "retail",
                "example.test",
                10,
                true,
                true,
                "PL",
                "PLN",
                List.of(),
                Map.of()
        ));
        return document;
    }
}
