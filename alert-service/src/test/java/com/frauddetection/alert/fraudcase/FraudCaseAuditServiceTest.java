package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudCaseAuditServiceTest {

    @Test
    void shouldSanitizeAuditDetailsBeforeAppending() {
        FraudCaseAuditRepository repository = mock(FraudCaseAuditRepository.class);
        when(repository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FraudCaseAuditService service = new FraudCaseAuditService(repository);

        service.append(
                "case-1",
                "analyst-1",
                FraudCaseAuditAction.CASE_ASSIGNED,
                FraudCaseStatus.OPEN,
                FraudCaseStatus.OPEN,
                Map.of(
                        "newAssignee", "investigator-1",
                        "stackTrace", "should-not-leak",
                        "idempotencyKey", "raw-key",
                        "payload_hash", "payload-hash",
                        "leaseOwner", "worker-1",
                        "longValue", "x".repeat(300)
                )
        );

        ArgumentCaptor<FraudCaseAuditEntryDocument> captor = ArgumentCaptor.forClass(FraudCaseAuditEntryDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDetails())
                .containsEntry("newAssignee", "investigator-1")
                .doesNotContainKeys("stackTrace", "idempotencyKey", "payload_hash", "leaseOwner");
        assertThat(captor.getValue().getDetails().get("longValue")).hasSize(256);
    }
}
