package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                Map.of("fraud-ops-admin", AnalystRole.FRAUD_OPS_ADMIN),
                Set.of()
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

    @Test
    void shouldRecordLowCardinalityMappingMissWithoutRawGroupValue() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OidcAnalystAuthoritiesMapper mapper = new OidcAnalystAuthoritiesMapper(new JwtSecurityProperties(
                true,
                false,
                "http://localhost:8086/realms/fraud-detection",
                "",
                "sub",
                "groups",
                Map.of("fraud-ops-admin", AnalystRole.FRAUD_OPS_ADMIN),
                Set.of()
        ), new AlertServiceMetrics(meterRegistry));
        OidcIdToken idToken = new OidcIdToken(
                "id-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of(
                        "sub", "operator-1",
                        "groups", List.of("unknown-bank-group")
                )
        );

        mapper.mapAuthorities(List.of(new OidcUserAuthority(idToken)));

        assertThat(meterRegistry.get("oidc_authority_mapping_misses_total")
                .tag("claim", "groups")
                .tag("outcome", "ignored")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.getMeters().getFirst().getId().getTags().toString())
                .doesNotContain("unknown-bank-group")
                .doesNotContain("operator-1")
                .doesNotContain("id-token-value");
    }

    @Test
    void shouldMapOnlyConfiguredOidcGroupToProductionPromotionScope() {
        OidcAnalystAuthoritiesMapper mapper = new OidcAnalystAuthoritiesMapper(new JwtSecurityProperties(
                true,
                false,
                "http://localhost:8086/realms/fraud-detection",
                "",
                "sub",
                "groups",
                Map.of("fraud-analyst", AnalystRole.ANALYST),
                Set.of("bank-production-model-promotion")
        ));
        OidcIdToken idToken = new OidcIdToken(
                "id-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("sub", "operator-1", "groups", List.of("fraud-analyst", "bank-production-model-promotion"))
        );

        assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(idToken))).stream()
                .map(GrantedAuthority::getAuthority))
                .contains(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION);
    }

    @Test
    void policyApprovalAndProductionAreIndependentExplicitOidcGrants() {
        JwtSecurityProperties properties = new JwtSecurityProperties(true, false,
                "https://issuer.example/realm", "", "sub", "groups",
                Map.of("fraud-reviewer", AnalystRole.REVIEWER,
                        "fraud-ops-admin", AnalystRole.FRAUD_OPS_ADMIN), Set.of());
        OidcAnalystAuthoritiesMapper mapper = new OidcAnalystAuthoritiesMapper(properties, null,
                new ModelPolicyIamMapping(Map.of(
                        "policy-approver", Set.of(ModelPolicyAuthority.APPROVE),
                        "policy-production", Set.of(ModelPolicyAuthority.PRODUCTION))));
        OidcIdToken ordinary = token(List.of("fraud-reviewer", "fraud-ops-admin"));
        OidcIdToken granted = token(List.of("policy-approver"));
        assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(ordinary))).stream()
                .map(GrantedAuthority::getAuthority)).doesNotContain(ModelPolicyAuthority.APPROVE);
        assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(granted))).stream()
                .map(GrantedAuthority::getAuthority)).contains(ModelPolicyAuthority.APPROVE)
                .doesNotContain(ModelPolicyAuthority.PRODUCTION);
    }

    @Test
    void emergencyRetirementMandatesAreAbsentFromGeneralReviewerAndFraudOpsAdmin() {
        JwtSecurityProperties properties = new JwtSecurityProperties(true, false,
                "https://issuer.example/realm", "", "sub", "groups",
                Map.of("fraud-reviewer", AnalystRole.REVIEWER,
                        "fraud-ops-admin", AnalystRole.FRAUD_OPS_ADMIN),
                Set.of("model-promotion-production"));
        OidcAnalystAuthoritiesMapper mapper = new OidcAnalystAuthoritiesMapper(properties, null,
                new ModelPolicyIamMapping(Map.of(
                        "model-promotion-emergency-requester",
                        Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST),
                        "model-promotion-emergency-authorizer",
                        Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE),
                        "model-promotion-retirement-operator", Set.of(AnalystAuthority.MODEL_PROMOTION_RETIRE))));
        assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(
                token(List.of("fraud-reviewer", "fraud-ops-admin", "model-promotion-production"))))).stream()
                .map(GrantedAuthority::getAuthority))
                .doesNotContain(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST,
                        AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE);
        assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(token(List.of(
                "fraud-reviewer", "model-promotion-retirement-operator",
                "model-promotion-emergency-requester", "model-promotion-production"))))).stream()
                .map(GrantedAuthority::getAuthority))
                .contains(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST,
                        AnalystAuthority.MODEL_PROMOTION_RETIRE,
                        AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION)
                .doesNotContain(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE);
    }

    private OidcIdToken token(List<String> groups) {
        return new OidcIdToken("id-token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "operator-1", "groups", groups));
    }
}
