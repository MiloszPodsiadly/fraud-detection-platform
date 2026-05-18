package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionDoesNotMeanFraudTest {

    @Test
    void modelAndEnumsDoNotContainFraudVerdictOrLegalProofLanguage() {
        Stream<String> names = Stream.concat(
                Arrays.stream(SuspiciousTransactionDocument.class.getDeclaredFields()).map(Field::getName),
                Stream.concat(
                        Arrays.stream(SuspiciousTransactionStatus.values()).map(Enum::name),
                        Arrays.stream(DetectionSource.values()).map(Enum::name)
                )
        ).map(name -> name.toLowerCase(Locale.ROOT));

        assertThat(names)
                .noneMatch(name -> name.contains("fraudconfirmed")
                        || name.contains("confirmedfraud")
                        || name.contains("verdict")
                        || name.contains("finaloutcome")
                        || name.contains("analystdecision")
                        || name.contains("legalproof"));
    }
}
