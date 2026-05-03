package com.frauddetection.alert.audit.read.failure;

import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditRepository;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditPolicy;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("failure-injection")
@Tag("invariant-proof")
class SensitiveReadAuditFailureInjectionTest {

    @Test
    void shouldKeepLocalFailOpenReadAvailableButObservableWhenAuditPersistenceFails() {
        Fixture fixture = new Fixture(false);

        assertThatCode(() -> fixture.service.audit(
                ReadAccessEndpointCategory.ALERT_DETAIL,
                ReadAccessResourceType.ALERT,
                "alert-1",
                1,
                fixture.request
        )).doesNotThrowAnyException();

        assertThat(fixture.meterRegistry.get("fraud_platform_read_access_audit_persistence_failures_total")
                .tag("endpoint_category", "alert_detail")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void shouldFailClosedForBankSensitiveReadWhenAuditPersistenceFails() {
        Fixture fixture = new Fixture(true);

        assertThatThrownBy(() -> fixture.service.audit(
                ReadAccessEndpointCategory.EXTERNAL_AUDIT_INTEGRITY,
                ReadAccessResourceType.EXTERNAL_AUDIT_INTEGRITY,
                null,
                1,
                fixture.request
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE")
                .hasMessageContaining("Sensitive read audit unavailable");

        assertThat(fixture.meterRegistry.get("fraud_platform_read_access_audit_persistence_failures_total")
                .tag("endpoint_category", "external_audit_integrity")
                .counter()
                .count()).isEqualTo(1.0);
    }

    private static final class Fixture {
        private final ReadAccessAuditRepository repository = mock(ReadAccessAuditRepository.class);
        private final CurrentAnalystUser currentAnalystUser = mock(CurrentAnalystUser.class);
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final HttpServletRequest request = mock(HttpServletRequest.class);
        private final SensitiveReadAuditService service;

        private Fixture(boolean failClosed) {
            when(currentAnalystUser.get()).thenReturn(Optional.of(new AnalystPrincipal(
                    "ops-admin",
                    Set.of(AnalystRole.FRAUD_OPS_ADMIN),
                    AnalystRole.FRAUD_OPS_ADMIN.authorities()
            )));
            when(request.getHeader("X-Correlation-Id")).thenReturn("corr-1");
            doThrow(new DataAccessResourceFailureException("mongo unavailable"))
                    .when(repository).save(any());
            ReadAccessAuditService delegate = new ReadAccessAuditService(
                    repository,
                    currentAnalystUser,
                    new AlertServiceMetrics(meterRegistry)
            );
            SensitiveReadAuditPolicy policy = mock(SensitiveReadAuditPolicy.class);
            when(policy.failClosed()).thenReturn(failClosed);
            service = new SensitiveReadAuditService(delegate, policy);
        }
    }
}
