package com.frauddetection.alert.audit.external;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalAuditIntegrityControllerTest {

    @Test
    void shouldRateLimitCoverageEndpoint() {
        ExternalAuditIntegrityService service = mock(ExternalAuditIntegrityService.class);
        ExternalAuditCoverageRateLimiter rateLimiter = mock(ExternalAuditCoverageRateLimiter.class);
        ExternalAuditIntegrityController controller = new ExternalAuditIntegrityController(service, rateLimiter);
        when(rateLimiter.allow()).thenReturn(false);

        assertThatThrownBy(() -> controller.coverage("alert-service", 100, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void shouldDelegateBoundedCoverageRequestWhenAllowed() {
        ExternalAuditIntegrityService service = mock(ExternalAuditIntegrityService.class);
        ExternalAuditCoverageRateLimiter rateLimiter = mock(ExternalAuditCoverageRateLimiter.class);
        ExternalAuditIntegrityController controller = new ExternalAuditIntegrityController(service, rateLimiter);
        ExternalAuditAnchorCoverageResponse response = new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                10,
                0,
                0L,
                List.of(),
                false,
                100,
                null,
                null
        );
        when(rateLimiter.allow()).thenReturn(true);
        when(service.coverage("alert-service", 100, 1L)).thenReturn(response);

        ExternalAuditAnchorCoverageResponse actual = controller.coverage("alert-service", 100, 1L);

        assertThat(actual).isSameAs(response);
        verify(service).coverage("alert-service", 100, 1L);
    }
}
