package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp40UnsupportedClaimsMatrixTest {

    private static final List<String> REQUIRED_NON_CLAIMS = List.of(
            "signed image does not mean external finality",
            "signed image does not mean business correctness",
            "signed image does not mean distributed ACID",
            "attestation does not mean legal notarization",
            "approval gate does not mean operational correctness",
            "rollback plan does not mean zero-downtime guarantee",
            "release image digest does not mean Kafka exactly-once",
            "environment approval does not mean bank certification",
            "READY_FOR_ENABLEMENT_REVIEW does not mean PRODUCTION_ENABLED",
            "fixture proof does not mean production image proof"
    );

    @Test
    void unsupportedClaimsMatrixContainsExactNonClaimLanguage() throws Exception {
        String matrix = Files.readString(Path.of("../docs/release/fdp_40_unsupported_claims_matrix.md"));

        for (String nonClaim : REQUIRED_NON_CLAIMS) {
            assertThat(matrix).contains(nonClaim);
        }
        assertThat(matrix)
                .doesNotContain("signed image means external finality")
                .doesNotContain("READY_FOR_ENABLEMENT_REVIEW means PRODUCTION_ENABLED");
    }
}
