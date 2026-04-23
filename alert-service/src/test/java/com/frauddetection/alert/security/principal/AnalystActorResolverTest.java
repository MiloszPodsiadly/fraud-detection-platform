package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystActorResolverTest {

    private final AnalystActorResolver resolver = new AnalystActorResolver(new CurrentAnalystUser());

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
