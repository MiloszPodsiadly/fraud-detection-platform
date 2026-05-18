package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceDoesNotImplyFraudTest {

    @Test
    void projectedEvidenceTextDoesNotClaimVerdictProofOrFinalOutcome() {
        EvidenceProjectionService service = new EvidenceProjectionService(
                new ReasonCodeEvidenceTypeMapper(),
                Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
        );

        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.CRITICAL,
                List.of("COUNTRY_MISMATCH", "RAPID_TRANSFER_FRAUD_CASE", "FRAUD_CONFIRMED")
        ));

        assertThat(evidence).allSatisfy(item -> {
            String text = (item.getTitle() + " " + item.getDescription()).toLowerCase(Locale.ROOT);
            assertThat(text).doesNotContain("confirmed fraud");
            assertThat(text).doesNotContain("proof of fraud");
            assertThat(text).doesNotContain("fraud proof");
            assertThat(text).doesNotContain("final outcome");
            assertThat(text).doesNotContain("analyst confirmed");
            assertThat(text).doesNotContain("legal proof");
            assertThat(text).doesNotContain("worm");
            assertThat(text).doesNotContain("notarized");
            assertThat(text).doesNotContain("fraud case exists");
            assertThat(text).doesNotContain("verdict");
        });
    }

    @Test
    void productDocumentationFramesEvidenceAsNonClaim() throws Exception {
        Path docsPath = Files.exists(Path.of("docs/product/evidence_model.md"))
                ? Path.of("docs/product/evidence_model.md")
                : Path.of("../docs/product/evidence_model.md");
        String docs = Files.readString(docsPath).toLowerCase(Locale.ROOT);

        assertThat(docs).contains("evidence is not confirmed fraud");
        assertThat(docs).contains("evidence is not final outcome");
        assertThat(docs).contains("evidence is not legal proof");
        assertThat(docs).contains("unknown must not be treated as supported evidence signal");
        assertThat(docs).doesNotContain("evidence proves fraud");
    }
}
