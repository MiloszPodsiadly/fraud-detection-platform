package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.security.auth.ModelPolicyIamMapping;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyGovernanceSecurityTest {
    @Test
    void policyMandatesComeOnlyFromExplicitIamMapping() {
        ModelPolicyIamMapping mapping = new ModelPolicyIamMapping(Map.of(
                "policy-approver", Set.of(ModelPolicyAuthority.APPROVE),
                "policy-production", Set.of(ModelPolicyAuthority.PRODUCTION)));
        assertThat(mapping.authorities(Set.of("fraud-reviewer", "fraud-ops-admin"))).isEmpty();
        assertThat(mapping.authorities(Set.of("policy-approver"))).containsExactly(ModelPolicyAuthority.APPROVE);
        assertThat(mapping.authorities(Set.of("policy-approver"))).doesNotContain(ModelPolicyAuthority.PRODUCTION);
        assertThatThrownBy(() -> new ModelPolicyIamMapping(Map.of("bad", Set.of("ROLE_FRAUD_OPS_ADMIN"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void governanceActorRequiresIssuerAndSubjectRatherThanDisplayUserIdOrRole() {
        AnalystPrincipal forged = new AnalystPrincipal("approver", Set.of(AnalystRole.FRAUD_OPS_ADMIN),
                Set.of(ModelPolicyAuthority.APPROVE));
        var forgedAuthentication = new UsernamePasswordAuthenticationToken(forged, null,
                List.of(new SimpleGrantedAuthority(ModelPolicyAuthority.APPROVE)));
        assertThatThrownBy(() -> PolicyGovernanceActor.from(forgedAuthentication))
                .isInstanceOf(AccessDeniedException.class);
        AnalystPrincipal trusted = new AnalystPrincipal("display-name", Set.of(), Set.of(ModelPolicyAuthority.APPROVE),
                "https://issuer.example/realm#stable-subject");
        var trustedAuthentication = new UsernamePasswordAuthenticationToken(trusted, null,
                List.of(new SimpleGrantedAuthority(ModelPolicyAuthority.APPROVE)));
        PolicyGovernanceActor actor = PolicyGovernanceActor.from(trustedAuthentication);
        assertThat(actor.id()).startsWith("oidc:").hasSize(69);
        assertThat(actor.id()).doesNotContain("display-name");
    }

    @Test
    void forgedAuthorAndPublisherFieldsAreNotAcceptedByDraftRequestContract() {
        String json = """
                {"policyId":"policy-1","revision":1,"governanceScope":{
                "institutionalScope":"CURRENT_INSTITUTION","environment":"TEST"},
                "intendedUse":"FRAUD_RISK_SCORING","intendedApproverActorId":"approver-1",
                "approvalReference":"review-1","effectiveFromInclusive":"2027-01-01T00:00:00Z",
                "effectiveToExclusive":"2027-02-01T00:00:00Z","criteria":[],
                "authorIdentity":"forged-author","publisherIdentity":"forged-publisher"}
                """;
        assertThatThrownBy(() -> new ObjectMapper().readValue(json,
                PolicyGovernanceContracts.DraftInput.class)).isInstanceOf(Exception.class)
                .hasMessageContaining("Unknown policy draft field");
    }
}
