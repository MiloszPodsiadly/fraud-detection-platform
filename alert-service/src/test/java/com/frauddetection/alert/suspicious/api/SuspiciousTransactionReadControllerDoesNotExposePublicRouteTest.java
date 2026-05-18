package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionReadControllerDoesNotExposePublicRouteTest {

    @Test
    void pathIsInternalAndDoesNotUseFraudTransactionWording() {
        String path = SuspiciousTransactionReadController.class.getAnnotation(RequestMapping.class).value()[0];

        assertThat(path).startsWith("/internal/");
        assertThat(path).doesNotContain("fraud-transactions", "confirmed-fraud", "case-transactions");
    }
}
