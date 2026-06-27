package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.AlertServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WriteActionAuditOutboxSchedulerTest {

    private final WriteActionAuditOutboxPublisher publisher = mock(WriteActionAuditOutboxPublisher.class);
    private final WriteActionAuditOutboxScheduler scheduler = new WriteActionAuditOutboxScheduler(publisher);

    @Test
    void scheduledTriggerCallsPublisher() {
        scheduler.publishPendingAuditIntents();

        verify(publisher).publishPending();
    }

    @Test
    void schedulerIsPropertyGatedAndEnabledByDefault() {
        ConditionalOnProperty property = WriteActionAuditOutboxScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(property).isNotNull();
        assertThat(property.prefix()).isEqualTo("app.audit.outbox.publisher");
        assertThat(property.name()).containsExactly("enabled");
        assertThat(property.havingValue()).isEqualTo("true");
        assertThat(property.matchIfMissing()).isTrue();
    }

    @Test
    void schedulerUsesConfiguredFixedDelayProperty() throws NoSuchMethodException {
        Scheduled scheduled = WriteActionAuditOutboxScheduler.class
                .getDeclaredMethod("publishPendingAuditIntents")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.audit.outbox.publisher.fixed-delay-ms:30000}");
    }

    @Test
    void alertServiceRuntimeEnablesScheduling() {
        assertThat(AlertServiceApplication.class.getAnnotation(EnableScheduling.class)).isNotNull();
    }
}
