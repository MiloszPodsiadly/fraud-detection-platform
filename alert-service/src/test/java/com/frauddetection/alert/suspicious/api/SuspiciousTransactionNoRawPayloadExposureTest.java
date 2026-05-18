package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionNoRawPayloadExposureTest {

    @Test
    void responseFieldNamesDoNotExposeRawPayloads() {
        assertThat(SuspiciousTransactionResponseContractTest.recordFieldNames())
                .noneMatch(field -> field.toLowerCase(java.util.Locale.ROOT).contains("payload"));
    }
}
