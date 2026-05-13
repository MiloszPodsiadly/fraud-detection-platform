package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OidcAnalystAuthoritiesMapperTest {

    @Test
    void shouldMapOidcGroupsToAnalystAuthoritiesWithoutExposingBearerToken() {
        OidcAnalystAuthoritiesMapper mapper = new OidcAnalystAuthoritiesMapper(new JwtSecurityProperties(
                true,
                false,
                "http://localhost:8086/realms/fraud-detection",
                "",
                "sub",
                "groups",
                Map.of("fraud-ops-admin", AnalystRole.FRAUD_OPS_ADMIN)
        ));
        OidcIdToken idToken = new OidcIdToken(
                "id-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of(
                        "sub", "operator-1",
                        "groups", List.of("fraud-ops-admin")
                )
        );

        assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(idToken))).stream()
                .map(GrantedAuthority::getAuthority))
                .contains("ROLE_FRAUD_OPS_ADMIN", AnalystAuthority.ALERT_READ)
                .doesNotContain("Bearer id-token-value");
    }
}
