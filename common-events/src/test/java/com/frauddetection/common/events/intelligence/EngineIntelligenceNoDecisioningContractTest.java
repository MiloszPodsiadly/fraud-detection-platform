package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceNoDecisioningContractTest {
    private static final List<String> FORBIDDEN = List.of(
            "finaldecision", "decision", "recommendedaction", "recommendation", "winningengine", "primaryengine",
            "approve", "approved", "decline", "declined", "block", "blocked", "authorizationdecision",
            "paymentdecision", "finalrisk", "finalscore", "platformriskscore", "platformrisklevel", "analystaction"
    );

    @Test
    void publicDtosContainNoDecisioningFields() {
        assertThat(EngineIntelligencePublicTypes.records().stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT)))
                .doesNotContainAnyElementsOf(FORBIDDEN);
    }

    @Test
    void serializedJsonContainsNoDecisioningFields() throws Exception {
        String json = EngineIntelligenceTestSupport.objectMapper()
                .writeValueAsString(EngineIntelligenceTestSupport.summary())
                .toLowerCase(Locale.ROOT);

        FORBIDDEN.forEach(field -> assertThat(json).doesNotContain("\"" + field + "\":"));
    }

    @Test
    void docsStateNoDecisioningSemantics() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/public_engine_intelligence_event_contract.md"))
                .toLowerCase(Locale.ROOT);

        assertThat(docs).contains(
                "agreement is not approval",
                "disagreement is not decline",
                "not a final score",
                "risk mismatch is not final decision",
                "diagnostic signals are not recommendations"
        );
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
