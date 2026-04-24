package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystActorResolverTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AnalystActorResolver resolver = new AnalystActorResolver(
            new CurrentAnalystUser(),
            new AlertServiceMetrics(meterRegistry)
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldUseAuthenticatedPrincipalAsActor() {
        authenticate("principal-1");

        String actorId = resolver.resolveActorId("principal-1", "SUBMIT_ANALYST_DECISION", "alert-1");

        assertThat(actorId).isEqualTo("principal-1");
    }

    @Test
    void shouldPreferAuthenticatedPrincipalWhenRequestActorDiffers() {
        authenticate("principal-1");

        String actorId = resolver.resolveActorId("payload-analyst", "UPDATE_FRAUD_CASE", "case-1");

        assertThat(actorId).isEqualTo("principal-1");
        assertThat(meterRegistry.get("fraud.security.actor.mismatches")
                .tags("action", "update_fraud_case")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFallbackToRequestActorWhenPrincipalIsMissing() {
        String actorId = resolver.resolveActorId("payload-analyst", "UPDATE_FRAUD_CASE", "case-1");

        assertThat(actorId).isEqualTo("payload-analyst");
    }

    private void authenticate(String userId) {
        AnalystPrincipal principal = new AnalystPrincipal(
                userId,
                Set.of(AnalystRole.ANALYST),
                AnalystRole.ANALYST.authorities()
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Set.of())
        );
    }
}
