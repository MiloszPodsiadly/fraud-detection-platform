package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRuleGroupRegistrationTest {

    @Test
    void everyAuthorizationRuleGroupIsRegisteredInComposerRegistryAndDocs() {
        Set<String> discoveredGroups = SecurityRuleSource.discoveredAuthorizationRuleGroups();
        String composer = SecurityRuleSource.source(
                "src/main/java/com/frauddetection/alert/security/config/AlertEndpointAuthorizationRules.java");
        String docs = SecurityRuleSource.sourceFromPath(
                SecurityRuleSource.repositoryFile("docs/security/endpoint_authorization_map.md"));

        assertThat(discoveredGroups).containsExactlyInAnyOrderElementsOf(SecurityRuleSource.ROUTE_GROUPS);
        assertThat(discoveredGroups).allSatisfy(group -> {
            assertThat(composer)
                    .as("Authorization rule group %s is not registered in AlertEndpointAuthorizationRules/docs/security map.", group)
                    .contains("new " + group + "()");
            assertThat(SecurityRuleSource.ROUTE_GROUPS)
                    .as("Authorization rule group %s is not registered in AlertEndpointAuthorizationRules/docs/security map.", group)
                    .contains(group);
            assertThat(docs)
                    .as("Authorization rule group %s is not registered in AlertEndpointAuthorizationRules/docs/security map.", group)
                    .contains(group);
        });
    }
}
