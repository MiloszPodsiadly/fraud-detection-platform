package com.frauddetection.enricher.config;

import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.CoverageClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.HistoryWindowClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Reason;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Signal;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.SignalFact;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.SourceAssertion;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.SourceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Status;
import com.frauddetection.enricher.merchant.DisabledMerchantRiskAdmissionAdapter;
import com.frauddetection.enricher.merchant.MerchantRiskAdmission;
import com.frauddetection.enricher.merchant.MerchantRiskAdmission.Denial;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureStore;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantRiskAdmissionConfigurationTest {
    private static final Instant TIME = Instant.parse("2026-10-29T10:00:00Z");
    private static final Denial DENIED = Denial.MERCHANT_RISK_ADMISSION_DISABLED_NO_TRUSTED_ISSUER;
    private static final String ENABLEMENT_FAILURE = "fraud.merchant-risk.admission.enabled cannot be enabled: "
            + "independent issuer verification, scoped occurrence binding, coverage and approved retention are unavailable";
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MerchantRiskAdmissionConfiguration.class);

    @Test
    void defaultsDisabledAndExposesOnlyADenialPortWithoutInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MerchantRiskAdmissionProperties.class).enabled()).isFalse();
            assertThat(context).hasSingleBean(MerchantRiskAdmission.class)
                    .hasSingleBean(DisabledMerchantRiskAdmissionAdapter.class)
                    .doesNotHaveBean(MongoClient.class)
                    .doesNotHaveBean(FeatureStore.class)
                    .doesNotHaveBean(TransactionEnrichedEventPublisher.class);
            assertThat(context.getBean(MerchantRiskAdmission.class).admit(claim(SourceAssertion.CLIENT_ASSERTED)))
                    .isEqualTo(DENIED);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "demo-auth", "test", "prod"})
    void enablingAdmissionFailsStartupInEveryProfile(String profile) {
        contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues(MerchantRiskAdmissionProperties.PROPERTY_NAME + "=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(ENABLEMENT_FAILURE);
                });
    }

    @Test
    void realLazyInitializationCannotDeferTheAdmissionGuard() {
        contextRunner.withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withPropertyValues(MerchantRiskAdmissionProperties.PROPERTY_NAME + "=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(ENABLEMENT_FAILURE);
                });
    }

    @Test
    void environmentEnablementOverrideAlsoFailsStartup() {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("merchant-test-environment",
                                Map.of("FRAUD_MERCHANT_RISK_ADMISSION_ENABLED", "true"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(ENABLEMENT_FAILURE);
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unverifiedClaims")
    void unverifiedClaimsNeverWriteHistoryOrPublish(String scenario, MerchantRiskEvidenceClaim unverifiedClaim) {
        MongoClient mongo = mock(MongoClient.class);
        FeatureStore redis = mock(FeatureStore.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);
        contextRunner.withBean(MongoClient.class, () -> mongo)
                .withBean(FeatureStore.class, () -> redis)
                .withBean(TransactionEnrichedEventPublisher.class, () -> publisher)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MerchantRiskAdmission admission = context.getBean(MerchantRiskAdmission.class);
                    assertThat(admission.admit(unverifiedClaim)).isEqualTo(DENIED);
                    assertThat(admission.admit(unverifiedClaim)).isEqualTo(DENIED);
                    assertThat(DENIED.toString()).doesNotContain(unverifiedClaim.scope().institutionRef(),
                            unverifiedClaim.scope().merchantRef(), unverifiedClaim.occurrence().payloadCommitmentClaim());
                    verifyNoInteractions(mongo, redis, publisher);
                });
    }

    @Test
    void claimedIssuerSecretsMismatchedKeysAndCoverageCannotBypassEnablementOrLeakInErrors() {
        String secretMarker = "test-only-secret-never-log";
        String institutionMarker = "test-only-institution-never-log";
        String sourceMarker = "test-only-source-never-log";
        contextRunner.withPropertyValues(
                        MerchantRiskAdmissionProperties.PROPERTY_NAME + "=true",
                        MerchantRiskAdmissionProperties.PREFIX + ".issuer-secret=" + secretMarker,
                        MerchantRiskAdmissionProperties.PREFIX + ".institution-id=" + institutionMarker,
                        MerchantRiskAdmissionProperties.PREFIX + ".source-system=" + sourceMarker,
                        MerchantRiskAdmissionProperties.PREFIX + ".key-version=1",
                        MerchantRiskAdmissionProperties.PREFIX + ".claimed-key-version=2",
                        MerchantRiskAdmissionProperties.PREFIX + ".authenticated=true",
                        MerchantRiskAdmissionProperties.PREFIX + ".coverage-complete=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(ENABLEMENT_FAILURE);
                    for (Throwable failure = context.getStartupFailure(); failure != null; failure = failure.getCause()) {
                        assertThat(failure.getMessage()).doesNotContain(secretMarker, institutionMarker, sourceMarker);
                    }
                });
    }

    @Test
    void claimedTrustPropertiesDoNotChangeTheDisabledDefault() {
        contextRunner.withPropertyValues(
                        MerchantRiskAdmissionProperties.PREFIX + ".authenticated=true",
                        MerchantRiskAdmissionProperties.PREFIX + ".source-system=BANK",
                        MerchantRiskAdmissionProperties.PREFIX + ".coverage-complete=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MerchantRiskAdmissionProperties.class).enabled()).isFalse();
                    assertThat(context.getBean(MerchantRiskAdmission.class).admit(claim(SourceAssertion.SYNTHETIC)))
                            .isEqualTo(DENIED);
                });
    }

    @Test
    void nullInputFailsClosedWithoutAnOutcomeOrPayloadInTheError() {
        contextRunner.run(context -> assertThatThrownBy(() -> context.getBean(MerchantRiskAdmission.class).admit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MERCHANT_RISK_ADMISSION_CLAIM_REQUIRED").hasNoCause());
    }

    @Test
    void applicationYamlKeepsBothAdmissionBoundariesDisabledAndExistingTtlUnchanged() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst();
        assertThat(application.getProperty(MerchantRiskAdmissionProperties.PROPERTY_NAME)).isEqualTo(false);
        assertThat(application.getProperty(DeviceRiskAdmissionProperties.PROPERTY_NAME)).isEqualTo(false);
        assertThat(application.getProperty("app.feature-store.last-transaction-ttl")).isEqualTo("P30D");
    }

    private static Stream<Arguments> unverifiedClaims() {
        MerchantRiskEvidenceClaim baseline = claim(SourceAssertion.CLIENT_ASSERTED);
        ScopeClaim scope = baseline.scope();
        HistoryWindowClaim sample = new HistoryWindowClaim(TIME.minusSeconds(60), TIME, TIME.minusSeconds(60), TIME,
                CoverageClaim.OBSERVED_SAMPLE);
        return Stream.of(
                Arguments.of("missing-coverage", baseline),
                Arguments.of("synthetic-input", claim(SourceAssertion.SYNTHETIC)),
                Arguments.of("no-issuer-authority", claim(SourceAssertion.UNSPECIFIED)),
                Arguments.of("sample-is-not-complete", new MerchantRiskEvidenceClaim(1, scope, baseline.occurrence(),
                        baseline.source(), sample, baseline.signals())),
                Arguments.of("forged-issuer", withScope(baseline,
                        new ScopeClaim(token('9'), scope.institutionRef(), scope.sourceRef(), scope.sourceEpochRef(), scope.merchantRef(), 1))),
                Arguments.of("forged-institution", withScope(baseline,
                        new ScopeClaim(scope.issuerRef(), token('9'), scope.sourceRef(), scope.sourceEpochRef(), scope.merchantRef(), 1))),
                Arguments.of("source-identity-conflict", withScope(baseline,
                        new ScopeClaim(scope.issuerRef(), scope.institutionRef(), token('9'), scope.sourceEpochRef(), scope.merchantRef(), 1))),
                Arguments.of("source-epoch-conflict", withScope(baseline,
                        new ScopeClaim(scope.issuerRef(), scope.institutionRef(), scope.sourceRef(), token('9'), scope.merchantRef(), 1))),
                Arguments.of("different-occurrence-and-commitment", new MerchantRiskEvidenceClaim(1, scope,
                        new OccurrenceClaim(token('9'), token('8'), token('7'), 2, TIME, token('6')),
                        baseline.source(), baseline.history(), baseline.signals())));
    }

    private static MerchantRiskEvidenceClaim withScope(MerchantRiskEvidenceClaim baseline, ScopeClaim scope) {
        return new MerchantRiskEvidenceClaim(1, scope, baseline.occurrence(), baseline.source(), baseline.history(), baseline.signals());
    }

    /** Test-only unadmitted assumptions; no fixture claims to authenticate an institution or key. */
    private static MerchantRiskEvidenceClaim claim(SourceAssertion assertion) {
        Status status = assertion == SourceAssertion.UNSPECIFIED ? Status.UNAVAILABLE : Status.UNTRUSTED;
        Reason reason = switch (assertion) {
            case CLIENT_ASSERTED -> Reason.CLIENT_ASSERTION;
            case SYNTHETIC -> Reason.SYNTHETIC_INPUT;
            case UNSPECIFIED -> Reason.NOT_ADMITTED;
        };
        return new MerchantRiskEvidenceClaim(1,
                new ScopeClaim(token('a'), token('b'), token('c'), token('d'), token('e'), 1),
                new OccurrenceClaim(token('f'), token('1'), token('2'), 1, TIME, token('3')),
                new SourceClaim(assertion, TIME),
                new HistoryWindowClaim(TIME.minusSeconds(60), TIME, null, null, CoverageClaim.INCOMPLETE),
                Arrays.stream(Signal.values()).map(signal -> new SignalFact(signal, status, reason)).toList());
    }

    private static String token(char character) {
        return String.valueOf(character).repeat(64);
    }
}
