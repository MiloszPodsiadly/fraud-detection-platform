package com.frauddetection.alert.security.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SecurityRuleSource {

    static final List<String> ROUTE_GROUPS = List.of(
            "PublicTechnicalAuthorizationRules",
            "SessionAuthorizationRules",
            "AlertAuthorizationRules",
            "FraudCaseAuthorizationRules",
            "TransactionAuthorizationRules",
            "GovernanceAuthorizationRules",
            "AuditAuthorizationRules",
            "TrustAuthorizationRules",
            "RecoveryAuthorizationRules",
            "BffAuthorizationRules",
            "SpaFallbackAuthorizationRules",
            "DenyByDefaultAuthorizationRules"
    );

    private SecurityRuleSource() {
    }

    static Map<String, String> routeGroupSourcesExceptDenyByDefault() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String className : ROUTE_GROUPS) {
            if (!"DenyByDefaultAuthorizationRules".equals(className)) {
                sources.put(className, source("src/main/java/com/frauddetection/alert/security/config/" + className + ".java"));
            }
        }
        return sources;
    }

    static Set<String> discoveredAuthorizationRuleGroups() {
        Path root = securityConfigSourceRoot();
        try (Stream<Path> files = Files.list(root)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith("AuthorizationRules.java"))
                    .map(fileName -> fileName.substring(0, fileName.length() - ".java".length()))
                    .filter(className -> !"AlertEndpointAuthorizationRules".equals(className))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan authorization rules under " + root, exception);
        }
    }

    static Set<String> discoveredApplicationControllerClasses() {
        Path root = repositoryFile("alert-service/src/main/java/com/frauddetection/alert");
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(SecurityRuleSource::isApplicationControllerSource)
                    .map(SecurityRuleSource::fullyQualifiedClassName)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan application controllers under " + root, exception);
        }
    }

    static String source(String relativePath) {
        Path base = Path.of("").toAbsolutePath();
        Path candidate = base.resolve(relativePath);
        if (!Files.exists(candidate)) {
            candidate = base.resolve("alert-service").resolve(relativePath);
        }
        try {
            return Files.readString(candidate);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + candidate, exception);
        }
    }

    static String sourceFromPath(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    static Path repositoryFile(String relativePath) {
        Path base = Path.of("").toAbsolutePath();
        Path candidate = base.resolve(relativePath);
        if (Files.exists(candidate)) {
            return candidate;
        }
        candidate = base.resolve("..").resolve(relativePath).normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        return base.resolve(relativePath);
    }

    private static Path securityConfigSourceRoot() {
        return repositoryFile("alert-service/src/main/java/com/frauddetection/alert/security/config");
    }

    private static boolean isApplicationControllerSource(Path path) {
        String source = sourceFromPath(path);
        String fileName = path.getFileName().toString();
        return !fileName.endsWith("Test.java")
                && !source.contains("@ControllerAdvice")
                && !source.contains("@RestControllerAdvice")
                && java.util.regex.Pattern.compile("@(RestController|Controller)(\\s|\\(|$)")
                        .matcher(source)
                        .find();
    }

    private static String fullyQualifiedClassName(Path path) {
        String source = sourceFromPath(path);
        String packageName = source.lines()
                .filter(line -> line.startsWith("package "))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing package in " + path))
                .replace("package ", "")
                .replace(";", "")
                .trim();
        String simpleName = path.getFileName().toString().replace(".java", "");
        return packageName + "." + simpleName;
    }
}
