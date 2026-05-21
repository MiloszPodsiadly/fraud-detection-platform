package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetrySink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
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
        SuspiciousTransactionReadController controller = controller(service, snapshot -> {
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
        SuspiciousTransactionReadController controller = controller(service, snapshot -> {
            throw new IllegalStateException("raw-secret-exception-message");
        });

        SuspiciousTransactionResponse actual = controller.findById("suspicious-1", new MockHttpServletRequest());

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void customSinkFailureLogIsBounded(CapturedOutput output) {
        SuspiciousTransactionReadService service = mock(SuspiciousTransactionReadService.class);
        SuspiciousTransactionSliceResponse expected = new SuspiciousTransactionSliceResponse(
                List.of(SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT"))),
                20,
                false,
                null
        );
        when(service.search(any())).thenReturn(expected);
        SuspiciousTransactionReadController controller = controller(service, snapshot -> {
            throw new IllegalStateException("raw-secret-exception-message customer-secret-123 cursor-secret-456");
        });
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("customerId", "customer-secret-123");
        params.add("cursor", "cursor-secret-456");

        controller.search(params, new MockHttpServletRequest());

        assertThat(output)
                .contains(
                        "query telemetry sink failed",
                        "endpoint=search",
                        "outcome=success",
                        "queryShape=customer"
                )
                .doesNotContain(
                        "raw-secret-exception-message",
                        "customer-secret-123",
                        "cursor-secret-456",
                        "IllegalStateException"
                );
    }

    private SuspiciousTransactionReadController controller(
            SuspiciousTransactionReadService service,
            SuspiciousTransactionQueryTelemetrySink telemetrySink
    ) {
        return new SuspiciousTransactionReadController(
                service,
                mock(SuspiciousTransactionLinkedAlertContextService.class),
                mock(SensitiveReadAuditService.class),
                mock(AlertServiceMetrics.class),
                new SuspiciousTransactionQueryTelemetryClassifier(),
                telemetrySink
        );
    }
}
