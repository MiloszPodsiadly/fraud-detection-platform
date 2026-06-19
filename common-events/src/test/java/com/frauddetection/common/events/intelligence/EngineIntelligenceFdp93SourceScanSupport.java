package com.frauddetection.common.events.intelligence;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

final class EngineIntelligenceFdp93SourceScanSupport {

    static final List<String> FDP97_ANALYST_CONSOLE_ENGINE_INTELLIGENCE_ALLOWED_FILES = List.of(
            "analyst-console-ui/src/api/alertsApi.js",
            "analyst-console-ui/src/api/alertsApi.test.js",
            "analyst-console-ui/src/components/EngineIntelligenceAnalystUiDisplayDocsTest.test.js",
            "analyst-console-ui/src/components/EngineIntelligenceFeedbackPanel.jsx",
            "analyst-console-ui/src/components/EngineIntelligenceFeedbackPanel.test.jsx",
            "analyst-console-ui/src/components/EngineIntelligencePanel.jsx",
            "analyst-console-ui/src/components/EngineIntelligencePanel.test.jsx",
            "analyst-console-ui/src/components/EngineIntelligencePanelScopeGuard.test.js",
            "analyst-console-ui/src/components/TransactionRiskIntelligencePanel.jsx",
            "analyst-console-ui/src/components/TransactionRiskIntelligencePanel.test.jsx",
            "analyst-console-ui/src/pages/FraudCaseDetailsPage.jsx",
            "analyst-console-ui/src/pages/FraudCaseDetailsPage.test.jsx",
            "analyst-console-ui/src/transactions/transactionRiskIntelligenceScopeGuard.test.js",
            "analyst-console-ui/src/transactions/transactionRiskIntelligenceValidation.js",
            "analyst-console-ui/src/transactions/transactionRiskIntelligenceValidation.test.js",
            "analyst-console-ui/src/transactions/useScoredTransactionDetail.test.js",
            "analyst-console-ui/src/styles.css"
    );

    private EngineIntelligenceFdp93SourceScanSupport() {
    }

    static String read(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath));
    }

    static String sources(String relativeRoot) throws IOException {
        Path root = repositoryRoot().resolve(relativeRoot);
        if (!Files.exists(root)) {
            return "";
        }
        try (Stream<Path> paths = Files.walk(root)) {
            StringBuilder sources = new StringBuilder();
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                sources.append(Files.readString(path)).append('\n');
            }
            return sources.toString();
        }
    }

    static List<String> productionJavaFilesContaining(String needle) throws IOException {
        Path root = repositoryRoot();
        return repositoryFiles(root).stream()
                .filter(path -> normalize(root.relativize(path)).contains("/src/main/"))
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !normalize(root.relativize(path)).contains("/generated/"))
                .filter(path -> fileContains(path, needle))
                .map(root::relativize)
                .map(EngineIntelligenceFdp93SourceScanSupport::normalize)
                .sorted()
                .toList();
    }

    static List<String> filesContainingAny(String relativeRoot, Collection<String> needles) throws IOException {
        Path repositoryRoot = repositoryRoot();
        Path scanRoot = repositoryRoot.resolve(relativeRoot);
        if (!Files.exists(scanRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(scanRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> needles.stream().anyMatch(needle -> fileContains(path, needle)))
                    .map(repositoryRoot::relativize)
                    .map(EngineIntelligenceFdp93SourceScanSupport::normalize)
                    .sorted()
                    .toList();
        }
    }

    static String productionConfigurationSources() throws IOException {
        Path root = repositoryRoot();
        StringBuilder sources = new StringBuilder();
        for (Path path : repositoryFiles(root)) {
            if (isProductionConfigurationPath(normalize(root.relativize(path)))) {
                sources.append(Files.readString(path)).append('\n');
            }
        }
        return sources.toString();
    }

    static Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("common-events")) && Files.exists(candidate.resolve("alert-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }

    private static boolean fileContains(Path path, String needle) {
        try {
            return Files.readString(path).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("FDP93_SOURCE_SCAN_READ_FAILED", exception);
        }
    }

    private static boolean isProductionConfigurationPath(String path) {
        return path.contains("/src/main/resources/")
                || path.startsWith("deployment/")
                || path.startsWith("docker/")
                || path.startsWith("helm/")
                || path.startsWith("k8s/")
                || path.startsWith("config/")
                || path.matches("docker-compose[^/]*\\.ya?ml");
    }

    private static List<Path> repositoryFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnoredRepositoryDirectory(normalize(root.relativize(dir)))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static boolean isIgnoredRepositoryDirectory(String path) {
        return path.equals(".git")
                || path.startsWith(".git/")
                || path.equals(".m2repo")
                || path.startsWith(".m2repo/")
                || path.equals("node_modules")
                || path.endsWith("/node_modules")
                || path.equals("target")
                || path.endsWith("/target");
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
