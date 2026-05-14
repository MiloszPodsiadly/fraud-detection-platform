package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SingleSecurityFilterChainGuardTest {

    @Test
    void alertServiceKeepsExactlyOneApplicationSecurityFilterChainBean() throws IOException {
        Path configRoot = SecurityRuleSource.repositoryFile(
                "alert-service/src/main/java/com/frauddetection/alert/security/config");
        List<Path> securitySources;
        try (var stream = Files.walk(configRoot)) {
            securitySources = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        List<Path> filesDeclaringSecurityFilterChain = securitySources.stream()
                .filter(path -> SecurityRuleSource.sourceFromPath(path).contains("SecurityFilterChain"))
                .toList();
        String alertSecurityConfig = SecurityRuleSource.source(
                "src/main/java/com/frauddetection/alert/security/config/AlertSecurityConfig.java");

        assertThat(filesDeclaringSecurityFilterChain)
                .extracting(path -> path.getFileName().toString())
                .containsExactly("AlertSecurityConfig.java");
        assertThat(alertSecurityConfig).contains("SecurityFilterChain alertSecurityFilterChain(");
        assertThat(count(alertSecurityConfig, "SecurityFilterChain alertSecurityFilterChain(")).isEqualTo(1);
    }

    private int count(String source, String needle) {
        int count = 0;
        int position = 0;
        while ((position = source.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }
}
