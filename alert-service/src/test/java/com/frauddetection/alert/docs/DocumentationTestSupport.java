package com.frauddetection.alert.docs;

import java.nio.file.Files;
import java.nio.file.Path;

final class DocumentationTestSupport {

    private DocumentationTestSupport() {
    }

    static Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))
                    && Files.exists(candidate.resolve("docs"))
                    && Files.exists(candidate.resolve("alert-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }

    static Path docsRoot() {
        return repositoryRoot().resolve("docs");
    }

    static Path relativeToRepository(Path path) {
        return repositoryRoot().relativize(path.toAbsolutePath().normalize());
    }
}
