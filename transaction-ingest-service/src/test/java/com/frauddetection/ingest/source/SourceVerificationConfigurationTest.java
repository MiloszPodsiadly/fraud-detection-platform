package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;


import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static com.frauddetection.ingest.source.TestSourceIssuer.*;
import static org.assertj.core.api.Assertions.assertThat;

class SourceVerificationConfigurationTest {
    @Test void independentlyBoundServerConfigurationSuppliesTheOnlyTrustedKeyAndScope() throws Exception {
        byte[] proof = proof(PAYLOAD, java.util.Map.of("eventTime", java.time.Instant.now().minusSeconds(10).toString()), 0);
        String prefix = "fraud.source-verification.keys[0].";
        new ApplicationContextRunner().withUserConfiguration(SourceVerificationConfiguration.class)
                .withPropertyValues(prefix + "issuer=" + ISSUER, prefix + "institution=institution-a",
                        prefix + "source=source-a", prefix + "source-epoch=epoch-a", prefix + "signing-key-version=key-1",
                        prefix + "algorithm=Ed25519", prefix + "public-key-base64=" + publicKey(0),
                        prefix + "not-before=2000-01-01T00:00:00Z", prefix + "not-after=2100-01-01T00:00:00Z",
                        prefix + "revoked=false", prefix + "purpose=" + PURPOSE, prefix + "audience=" + AUDIENCE,
                        prefix + "policy-version=policy-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SourceSignatureVerifier.class).verify(proof, PAYLOAD,
                            new SourceSignatureVerifier.Context(PURPOSE, AUDIENCE)))
                            .isInstanceOf(SourceSignatureVerifier.Verified.class);
                });
    }

    @Test void defaultRuntimeHasNoTrustedIssuerAndNeverGrantsSyntheticSourceAdmission() throws Exception {
        byte[] proof = proof();
        new ApplicationContextRunner().withUserConfiguration(SourceVerificationConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SourceVerificationProperties.class).keys()).isEmpty();
            assertThat(context.getBean(SourceSignatureVerifier.class).verify(proof, PAYLOAD,
                    new SourceSignatureVerifier.Context(PURPOSE, AUDIENCE)))
                    .isEqualTo(SourceSignatureVerifier.Denied.UNKNOWN_TRUST_ANCHOR);
            assertThat(context.getBean(SourceSignatureVerifier.class).verify(null, PAYLOAD,
                    new SourceSignatureVerifier.Context(PURPOSE, AUDIENCE)))
                    .isEqualTo(SourceSignatureVerifier.Denied.PROOF_ABSENT);
        });
    }

    @Test void testIssuerAndKeysAreAbsentFromProductionSourcesAndDeploymentConfiguration() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("transaction-ingest-service")) { root = root.getParent(); }
        var productionRoots = new ArrayList<Path>();
        try (var modules = Files.list(root)) {
            modules.filter(Files::isDirectory).map(module -> module.resolve("src/main"))
                    .filter(Files::isDirectory).forEach(productionRoots::add);
        }
        productionRoots.add(root.resolve("deployment"));
        for (Path production : productionRoots) {
            try (var files = Files.walk(production)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String contents = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    assertThat(contents).as("production path %s", root.relativize(file))
                            .doesNotContain(ISSUER, SEEDS[0], SEEDS[1], publicKey(0), publicKey(1), "TestSourceIssuer");
                }
            }
        }
    }
}
