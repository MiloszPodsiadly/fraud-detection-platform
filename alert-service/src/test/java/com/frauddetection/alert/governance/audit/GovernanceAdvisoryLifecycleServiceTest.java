package com.frauddetection.alert.governance.audit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceAdvisoryLifecycleServiceTest {

    private final GovernanceAuditRepository repository = mock(GovernanceAuditRepository.class);
    private final GovernanceAdvisoryLifecycleService service = new GovernanceAdvisoryLifecycleService(repository);

    @Test
    void shouldReturnUnknownWhenAuditRepositoryThrows() {
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1"))
                .thenThrow(new DataAccessResourceFailureException("mongo unavailable"));

        GovernanceAdvisoryLifecycleStatus status = service.lifecycleStatus("advisory-1");

        assertThat(status).isEqualTo(GovernanceAdvisoryLifecycleStatus.UNKNOWN);
    }

    @Test
    void shouldReturnOpenOnlyWhenAuditQuerySucceededAndNoEventsExist() {
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1"))
                .thenReturn(Optional.empty());

        GovernanceAdvisoryLifecycleStatus status = service.lifecycleStatus("advisory-1");

        assertThat(status).isEqualTo(GovernanceAdvisoryLifecycleStatus.OPEN);
    }

    @Test
    void shouldReturnResolvedLifecycleWhenAuditDecisionExists() {
        GovernanceAuditEventDocument document = new GovernanceAuditEventDocument();
        document.setDecision(GovernanceAuditDecision.ACKNOWLEDGED);
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1"))
                .thenReturn(Optional.of(document));

        GovernanceAdvisoryLifecycleStatus status = service.lifecycleStatus("advisory-1");

        assertThat(status).isEqualTo(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED);
    }
}
