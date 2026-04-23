package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentAnalystUserTest {

    private final CurrentAnalystUser currentAnalystUser = new CurrentAnalystUser();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnEmptyWhenAuthenticationIsMissing() {
        assertThat(currentAnalystUser.get()).isEmpty();
    }

    @Test
    void shouldReturnAnalystPrincipalFromSecurityContext() {
        var principal = new AnalystPrincipal(
                "analyst-1",
                Set.of(AnalystRole.ANALYST),
                AnalystRole.ANALYST.authorities()
        );
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(AnalystAuthority.ALERT_READ))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(currentAnalystUser.get())
                .contains(principal);
    }
}
