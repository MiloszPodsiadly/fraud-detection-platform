package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditIntegrityScheduledVerifierTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AuditIntegrityService.class, () -> {
                AuditIntegrityService service = mock(AuditIntegrityService.class);
                when(service.verifyScheduled("alert-service", 500)).thenReturn(new AuditIntegrityResponse(
                        "VALID",
                        0,
                        500,
                        "HEAD",
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        "source_service:alert-service",
                        null,
                        List.of()
                ));
                return service;
            })
            .withBean(AlertServiceMetrics.class, () -> new AlertServiceMetrics(new SimpleMeterRegistry()))
            .withUserConfiguration(ScheduledVerifierConfiguration.class);

    @Test
    void shouldNotCreateScheduledVerifierByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AuditIntegrityScheduledVerifier.class));
    }

    @Test
    void shouldCreateScheduledVerifierWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("app.audit.integrity.scheduled-verification-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditIntegrityScheduledVerifier.class);
                    context.getBean(AuditIntegrityScheduledVerifier.class).verifyLatestWindow();
                    verify(context.getBean(AuditIntegrityService.class)).verifyScheduled("alert-service", 500);
                });
    }

    @Configuration
    @Import(AuditIntegrityScheduledVerifier.class)
    static class ScheduledVerifierConfiguration {
    }
}
