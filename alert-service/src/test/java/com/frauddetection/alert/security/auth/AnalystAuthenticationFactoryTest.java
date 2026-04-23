package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalystAuthenticationFactoryTest {

    private final AnalystAuthenticationFactory factory = new AnalystAuthenticationFactory();

    @Test
    void shouldCreateSpringAuthenticationFromAnalystPrincipal() {
        var principal = new AnalystPrincipal(
                "analyst-1",
                Set.of(AnalystRole.REVIEWER),
                Set.of(AnalystAuthority.ALERT_DECISION_SUBMIT, AnalystAuthority.FRAUD_CASE_UPDATE)
        );

        var authentication = factory.authenticated(principal);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(principal);
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .contains(
                        AnalystAuthority.ALERT_DECISION_SUBMIT,
                        AnalystAuthority.FRAUD_CASE_UPDATE,
                        "ROLE_REVIEWER"
                );
    }

    @Test
    void shouldRejectBlankAnalystUserId() {
        var principal = new AnalystPrincipal(" ", Set.of(AnalystRole.ANALYST), AnalystRole.ANALYST.authorities());

        assertThatThrownBy(() -> factory.authenticated(principal))
                .isInstanceOf(BadCredentialsException.class);
    }
}
