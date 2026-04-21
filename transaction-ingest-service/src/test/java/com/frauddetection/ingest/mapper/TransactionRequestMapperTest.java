package com.frauddetection.ingest.mapper;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.ingest.controller.TransactionIngestRequestTestData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRequestMapperTest {

    private final TransactionRequestMapper mapper = new TransactionRequestMapper();

    @Test
    void shouldMapRequestToRawEvent() {
        TransactionRawEvent event = mapper.toEvent(TransactionIngestRequestTestData.validRequest(), "corr-1001");

        assertThat(event.transactionId()).isEqualTo("txn-1001");
        assertThat(event.customerId()).isEqualTo("cust-1001");
        assertThat(event.accountId()).isEqualTo("acct-1001");
        assertThat(event.correlationId()).isEqualTo("corr-1001");
        assertThat(event.transactionAmount().amount()).isEqualByComparingTo("1249.99");
        assertThat(event.merchantInfo().merchantName()).isEqualTo("Northwind Electronics");
        assertThat(event.deviceInfo().deviceId()).isEqualTo("device-1001");
        assertThat(event.locationInfo().countryCode()).isEqualTo("US");
        assertThat(event.customerContext().emailDomain()).isEqualTo("example.com");
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.createdAt()).isNotNull();
    }
}
