package com.frauddetection.simulator.service;

import com.frauddetection.simulator.config.SyntheticReplayProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticReplayDataSourceTest {

    @Test
    void shouldGenerateSyntheticTrafficWithRareSuspiciousTransactions() {
        SyntheticReplayDataSource dataSource = new SyntheticReplayDataSource(
                new SyntheticReplayProperties(
                        Instant.parse("2026-01-01T08:00:00Z"),
                        20,
                        10,
                        4,
                        30L
                )
        );

        try (var stream = dataSource.stream(100)) {
            var events = stream.toList();
            assertThat(events).hasSize(100);
            assertThat(events.getFirst().sourceSystem()).isEqualTo("SYNTHETIC_GENERATOR");
            assertThat(events.stream().filter(event -> "LOW".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(80);
            assertThat(events.stream().filter(event -> "MEDIUM".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(11);
            assertThat(events.stream().filter(event -> "HIGH".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(7);
            assertThat(events.stream().filter(event -> "CRITICAL".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(2);
            assertThat(events.get(79).attributes()).containsEntry("suspicious", false);
            assertThat(events.get(80).attributes()).containsEntry("suspicious", true);
            assertThat(events.get(97).locationInfo().highRiskCountry()).isTrue();
        }
    }
}
