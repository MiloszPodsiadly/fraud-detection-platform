package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRouteOwnershipRegistryTest {

    @Test
    void registryOwnersExistAndClaimTheirRouteFamiliesInSource() {
        assertThat(SecurityRouteOwnershipRegistry.MVC_ROUTES)
                .allSatisfy(route -> {
                    assertThat(SecurityRuleSource.ROUTE_GROUPS).contains(route.owner());
                    String ownerSource = SecurityRuleSource.source(
                            "src/main/java/com/frauddetection/alert/security/config/" + route.owner() + ".java");
                    assertThat(ownerSource)
                            .as("MVC route %s %s declares owner %s, but the owner route group does not appear to claim this route family.",
                                    route.method(), route.pattern(), route.owner())
                            .contains(routeFamily(route.pattern()));
                });
    }

    @Test
    void productionSecurityConfigurationNeverUsesTestOwnershipRegistry() throws IOException {
        var productionRoot = SecurityRuleSource.repositoryFile("alert-service/src/main/java");
        List<String> references;
        try (var stream = Files.walk(productionRoot)) {
            references = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> SecurityRuleSource.sourceFromPath(path).contains("SecurityRouteOwnershipRegistry"))
                    .map(path -> productionRoot.relativize(path).toString())
                    .toList();
        }

        assertThat(references)
                .as("SecurityRouteOwnershipRegistry must never be used by production security configuration.")
                .isEmpty();
    }

    @Test
    void registryRemainsTestScoped() throws IOException {
        var repositoryRoot = SecurityRuleSource.repositoryFile(".");
        List<String> registryFiles;
        try (var stream = Files.walk(repositoryRoot)) {
            registryFiles = stream
                    .filter(path -> path.getFileName().toString().equals("SecurityRouteOwnershipRegistry.java"))
                    .map(path -> repositoryRoot.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }

        assertThat(registryFiles)
                .as("SecurityRouteOwnershipRegistry must never be used by production security configuration.")
                .hasSize(1)
                .allSatisfy(path -> assertThat(path)
                        .endsWith("src/test/java/com/frauddetection/alert/security/config/SecurityRouteOwnershipRegistry.java"))
                .noneSatisfy(path -> assertThat(path).contains("src/main/java"));
    }

    private String routeFamily(String pattern) {
        int variableIndex = pattern.indexOf("/{");
        if (variableIndex >= 0) {
            return pattern.substring(0, variableIndex);
        }
        return pattern;
    }
}
