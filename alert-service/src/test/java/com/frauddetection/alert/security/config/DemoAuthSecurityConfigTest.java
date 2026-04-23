package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.AnalystPrincipalResolver;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DemoAuthSecurityConfigTest {

    private final DemoAuthSecurityConfig config = new DemoAuthSecurityConfig();
    private final AnalystPrincipalResolver resolver = request -> Optional.empty();
    private final AnalystAuthenticationFactory authenticationFactory = new AnalystAuthenticationFactory();
    private final ApiAuthenticationEntryPoint entryPoint = mock(ApiAuthenticationEntryPoint.class);

    @Test
    void shouldCreateDemoAuthFilterForLocalProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker-local");

        assertThatCode(() -> config.demoAuthFilter(resolver, authenticationFactory, entryPoint, environment))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDemoAuthForNonLocalProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> config.demoAuthFilter(resolver, authenticationFactory, entryPoint, environment))
                .isInstanceOf(BeanCreationException.class)
                .hasMessageContaining("Demo header authentication can only be enabled");
    }
}
