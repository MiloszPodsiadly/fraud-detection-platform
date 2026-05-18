package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionApiNamingDoesNotUseFraudVerdictLanguageTest {

    @Test
    void controllerResponseAndPathDoNotUseFraudTransactionNaming() {
        String controllerName = SuspiciousTransactionReadController.class.getSimpleName();
        String responseName = SuspiciousTransactionResponse.class.getSimpleName();
        String path = SuspiciousTransactionReadController.class.getAnnotation(RequestMapping.class).value()[0];

        assertThat(controllerName).doesNotContain("FraudTransaction");
        assertThat(responseName).doesNotContain("FraudTransaction");
        assertThat(path).doesNotContain("fraud-transactions");
    }

    @Test
    void fieldNamesDoNotContainVerdictLanguage() {
        assertThat(SuspiciousTransactionResponseContractTest.recordFieldNames()).doesNotContain(
                "fraudConfirmed",
                "verdict",
                "finalOutcome",
                "analystDecision",
                "legalProof",
                "caseDecision"
        );
    }

    @Test
    void docsDoNotClaimConfirmedFraud() throws Exception {
        String docs = Files.readString(Path.of("../docs/product/suspicious_transaction_read_api.md"));

        assertThat(docs).contains("does not mean confirmed fraud");
        assertThat(docs).doesNotContain("records confirmed fraud", "FraudTransaction API");
    }
}
