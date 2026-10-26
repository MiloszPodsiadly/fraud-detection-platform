package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.DemoAuthFilter;
import com.frauddetection.alert.security.auth.JwtSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

class AuthenticationStartupGuardTest {

    @Test
    void shouldRejectBankProfileWhenJwtDecoderIsMissing() {
        AuthenticationStartupGuard guard = guard(new String[]{"bank"}, true, true, false, null, null);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JwtDecoder");
    }

    @Test
    void shouldRejectProdProfileWhenJwtDecoderIsMissing() {
        AuthenticationStartupGuard guard = guard(new String[]{"prod"}, true, true, false, null, null);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JwtDecoder");
    }

    @Test
    void shouldRejectBankProfileWhenJwtRequiredIsFalse() {
        AuthenticationStartupGuard guard = guard(new String[]{"bank"}, true, false, false, mock(JwtDecoder.class), null);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.required");
    }

    @Test
    void shouldRejectBankProfileWhenDemoAuthIsEnabled() {
        AuthenticationStartupGuard guard = guard(new String[]{"bank"}, true, true, true, mock(JwtDecoder.class), null);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.demo-auth.enabled");
    }

    @Test
    void shouldRejectProdProfileWhenDemoAuthIsEnabled() {
        AuthenticationStartupGuard guard = guard(new String[]{"prod"}, false, true, true, mock(JwtDecoder.class), null);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.demo-auth.enabled");
    }

    @Test
    void shouldRejectBankProfileWhenDemoAuthFilterBeanIsPresent() {
        AuthenticationStartupGuard guard = guard(new String[]{"bank"}, true, true, false, mock(JwtDecoder.class), mock(DemoAuthFilter.class));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DemoAuthFilter");
    }

    @Test
    void shouldAllowLocalProfileWithDemoAuthEnabled() {
        AuthenticationStartupGuard guard = guard(new String[]{"local"}, false, false, true, null, mock(DemoAuthFilter.class));

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void shouldAllowBankProfileWithJwtDecoderPresentAndDemoAuthDisabled() {
        AuthenticationStartupGuard guard = guard(new String[]{"bank"}, true, true, false, mock(JwtDecoder.class), null);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectProtectedProfileWhenTrustedIssuerIsMissing() {
        AuthenticationStartupGuard guard = guardWithJwtTrust(new String[]{"bank"}, true, true, false,
                mock(JwtDecoder.class), null, null, "fraud-detection-platform-api");

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.issuer-uri");
    }

    @Test
    void shouldRejectProtectedProfileWhenExpectedAudienceIsMissing() {
        AuthenticationStartupGuard guard = guardWithJwtTrust(new String[]{"staging"}, false, true, false,
                mock(JwtDecoder.class), null, "https://issuer.example.test", null);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.expected-audience");
    }

    @Test
    void shouldRequireIssuerAndAudienceWhenBankFailClosedIsEnabledOnAnUnexpectedProfile() {
        AuthenticationStartupGuard guard = guardWithJwtTrust(new String[]{"local"}, true, true, false,
                mock(JwtDecoder.class), null, "https://issuer.example.test", null);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.expected-audience");
    }

    @SuppressWarnings("unchecked")
    private AuthenticationStartupGuard guard(
            String[] profiles,
            boolean bankModeFailClosed,
            boolean jwtRequired,
            boolean demoAuthEnabled,
            JwtDecoder jwtDecoder,
            DemoAuthFilter demoAuthFilter
    ) {
        return guardWithJwtTrust(profiles, bankModeFailClosed, jwtRequired, demoAuthEnabled,
                jwtDecoder, demoAuthFilter, "https://issuer.example.test", "fraud-detection-platform-api");
    }

    @SuppressWarnings("unchecked")
    private AuthenticationStartupGuard guardWithJwtTrust(
            String[] profiles,
            boolean bankModeFailClosed,
            boolean jwtRequired,
            boolean demoAuthEnabled,
            JwtDecoder jwtDecoder,
            DemoAuthFilter demoAuthFilter,
            String issuerUri,
            String expectedAudience
    ) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        ObjectProvider<JwtDecoder> jwtProvider = mock(ObjectProvider.class);
        when(jwtProvider.getIfAvailable()).thenReturn(jwtDecoder);
        ObjectProvider<DemoAuthFilter> demoProvider = mock(ObjectProvider.class);
        when(demoProvider.getIfAvailable()).thenReturn(demoAuthFilter);
        return new AuthenticationStartupGuard(
                environment,
                jwtProvider,
                demoProvider,
                bankModeFailClosed,
                new JwtSecurityProperties(true, jwtRequired, issuerUri,
                        "https://issuer.example.test/jwks", "sub", "groups", Map.of(), Set.of(),
                        expectedAudience, false),
                demoAuthEnabled
        );
    }
}
