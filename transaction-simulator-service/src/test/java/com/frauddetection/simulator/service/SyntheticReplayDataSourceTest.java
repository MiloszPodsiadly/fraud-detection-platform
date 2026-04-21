package com.frauddetection.simulator.service;

import com.frauddetection.simulator.config.SyntheticReplayProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

        try (var stream = dataSource.stream(2_000)) {
            var events = stream.toList();
            assertThat(events).hasSize(2_000);
            assertThat(events.getFirst().sourceSystem()).isEqualTo("SYNTHETIC_GENERATOR");
            assertThat(events.stream().filter(event -> "LOW".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(1_991);
            assertThat(events.stream().filter(event -> "MEDIUM".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(4);
            assertThat(events.stream().filter(event -> "HIGH".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(4);
            assertThat(events.stream().filter(event -> "CRITICAL".equals(event.customerContext().attributes().get("expectedRiskLevel")))).hasSize(1);
            assertThat(events.get(994).attributes()).containsEntry("suspicious", false);
            assertThat(events.get(995).attributes()).containsEntry("suspicious", true);
            assertThat(events.subList(993, 996))
                    .extracting(event -> event.transactionAmount().currency())
                    .containsOnly("PLN");
            assertThat(events.subList(993, 996))
                    .extracting(event -> event.transactionAmount().amount())
                    .doesNotHaveDuplicates();
            assertThat(events.subList(993, 996).stream()
                    .map(event -> event.transactionAmount().amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isGreaterThan(new BigDecimal("20000.00"));
            assertThat(events.subList(993, 996))
                    .extracting("customerId")
                    .containsOnly(events.get(995).customerId());
            assertThat(events.get(995).transactionTimestamp().getEpochSecond() - events.get(993).transactionTimestamp().getEpochSecond())
                    .isLessThan(60);
            assertThat(events.get(998).locationInfo().highRiskCountry()).isTrue();
            assertThat(events.subList(1_994, 1_996))
                    .extracting("customerId")
                    .containsOnly(events.get(1_995).customerId());
            assertThat(events.subList(1_994, 1_996).stream()
                    .map(event -> event.transactionAmount().amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isGreaterThan(new BigDecimal("20000.00"));
        }
    }
}
