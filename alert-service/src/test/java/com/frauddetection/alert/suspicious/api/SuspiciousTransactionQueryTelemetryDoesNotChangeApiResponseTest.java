package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuspiciousTransactionQueryTelemetryDoesNotChangeApiResponseTest {

    @Test
    void telemetryFailureDoesNotFailSearchResponse() {
        SuspiciousTransactionReadService service = mock(SuspiciousTransactionReadService.class);
        SuspiciousTransactionSliceResponse expected = new SuspiciousTransactionSliceResponse(
                List.of(SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))),
                20,
                false,
                null
        );
        when(service.search(any())).thenReturn(expected);
        SuspiciousTransactionReadController controller = controller(service);
        controller.setQueryTelemetry(new SuspiciousTransactionQueryTelemetryClassifier(), snapshot -> {
            throw new IllegalStateException("raw-secret-exception-message");
        });

        SuspiciousTransactionSliceResponse actual = controller.search(
                new org.springframework.util.LinkedMultiValueMap<>(),
                new MockHttpServletRequest()
        );

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void telemetryFailureDoesNotFailReadResponse() {
        SuspiciousTransactionReadService service = mock(SuspiciousTransactionReadService.class);
        SuspiciousTransactionResponse expected = SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"));
        when(service.findById("suspicious-1")).thenReturn(Optional.of(expected));
        SuspiciousTransactionReadController controller = controller(service);
        controller.setQueryTelemetry(new SuspiciousTransactionQueryTelemetryClassifier(), snapshot -> {
            throw new IllegalStateException("raw-secret-exception-message");
        });

        SuspiciousTransactionResponse actual = controller.findById("suspicious-1", new MockHttpServletRequest());

        assertThat(actual).isEqualTo(expected);
    }

    private SuspiciousTransactionReadController controller(SuspiciousTransactionReadService service) {
        return new SuspiciousTransactionReadController(
                service,
                mock(SensitiveReadAuditService.class),
                mock(AlertServiceMetrics.class)
        );
    }
}
