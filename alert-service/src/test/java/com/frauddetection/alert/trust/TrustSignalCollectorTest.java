package com.frauddetection.alert.trust;

import com.frauddetection.alert.outbox.FraudAlertOutboxBacklogMonitor;
import com.frauddetection.alert.outbox.FraudAlertOutboxBacklogResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustSignalCollectorTest {

    @Test
    void reportsFraudAlertTerminalAndUnknownPublicationStates() {
        FraudAlertOutboxBacklogMonitor backlogMonitor = mock(FraudAlertOutboxBacklogMonitor.class);
        when(backlogMonitor.snapshot()).thenReturn(new FraudAlertOutboxBacklogResponse(0, 0, 0, 2, 1, 30L));
        TrustSignalCollector collector = new TrustSignalCollector(
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(backlogMonitor)
        );

        List<TrustSignal> signals = collector.collect();

        assertThat(signals)
                .extracting(TrustSignal::type, TrustSignal::source)
                .containsExactlyInAnyOrder(
                        tuple("OUTBOX_TERMINAL_FAILURE", "fraud_alert_outbox"),
                        tuple("OUTBOX_PUBLISH_CONFIRMATION_UNKNOWN", "fraud_alert_outbox")
                );
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
