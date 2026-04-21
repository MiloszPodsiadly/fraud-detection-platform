package com.frauddetection.simulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.simulator.config.JsonlReplayProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonlReplayDataSourceTest {

    @Test
    void shouldReadCanonicalReplayEventsFromJsonl() throws Exception {
        Path file = Files.createTempFile("replay-", ".jsonl");
        Files.writeString(file, """
                {"eventId":"event-1","transactionId":"txn-1","correlationId":"corr-1","customerId":"cust-1","accountId":"acct-1","paymentInstrumentId":"card-1","createdAt":"2026-01-01T00:00:00Z","transactionTimestamp":"2026-01-01T00:00:05Z","transactionAmount":{"amount":125.50,"currency":"EUR"},"merchantInfo":{"merchantId":"merchant-1","merchantName":"Trusted Merchant","merchantCategoryCode":"5411","merchantCategory":"Groceries","acquiringCountryCode":"DE","channel":"ECOMMERCE","cardPresent":false,"attributes":{"synthetic":true}},"deviceInfo":{"deviceId":"device-1","fingerprint":"fp-1","ipAddress":"203.0.113.1","userAgent":"Mozilla/5.0","platform":"IOS","browser":"CHROME","trustedDevice":true,"proxyDetected":false,"vpnDetected":false,"attributes":{"synthetic":true}},"locationInfo":{"countryCode":"DE","region":"Berlin","city":"Berlin","postalCode":"10115","latitude":52.52,"longitude":13.405,"timezone":"Europe/Berlin","highRiskCountry":false},"customerContext":{"customerId":"cust-1","accountId":"acct-1","segment":"STANDARD","emailDomain":"customer.example","accountAgeDays":400,"emailVerified":true,"phoneVerified":true,"homeCountryCode":"DE","preferredCurrency":"EUR","knownDeviceIds":["device-1"],"attributes":{"synthetic":true}},"transactionType":"CARD_PURCHASE","authorizationMethod":"3DS","sourceSystem":"JSONL_GENERATOR","traceId":"trace-1","attributes":{"generator":"jsonl-script"}}
                """);

        JsonlReplayDataSource dataSource = new JsonlReplayDataSource(
                new ObjectMapper().registerModule(new JavaTimeModule()),
                new JsonlReplayProperties(file.toString())
        );

        try (var stream = dataSource.stream(10)) {
            var events = stream.toList();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().transactionId()).isEqualTo("txn-1");
            assertThat(events.getFirst().sourceSystem()).isEqualTo("JSONL_GENERATOR");
        }
    }
}
