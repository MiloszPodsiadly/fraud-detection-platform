package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceNoFakeSecurityClaimsTest {

    @Test
    void contractNamesDoNotClaimVerdictProofOrExternalAuthority() {
        Stream<String> names = Stream.concat(
                Arrays.stream(ScoringEvidenceItem.class.getRecordComponents()).map(RecordComponent::getName),
                Stream.of(
                        Arrays.stream(ScoringEvidenceType.values()).map(Enum::name),
                        Arrays.stream(ScoringEvidenceSource.values()).map(Enum::name),
                        Arrays.stream(ScoringEvidenceStatus.values()).map(Enum::name),
                        Arrays.stream(ScoringEvidenceSeverity.values()).map(Enum::name)
                ).flatMap(stream -> stream)
        );

        assertThat(names.map(name -> name.toLowerCase(Locale.ROOT)))
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("confirmed")
                        .doesNotContain("proof")
                        .doesNotContain("finaloutcome")
                        .doesNotContain("analystdecision")
                        .doesNotContain("legal")
                        .doesNotContain("worm")
                        .doesNotContain("notar")
                        .doesNotContain("externalauthority"));
    }
}
