package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueCursorArchitectureTest {

    @Test
    void cursorRepositoryPathShouldUseKeysetPredicateWithoutMongoSkipOrExactCount() throws IOException {
        String repository = Files.readString(sourceRoot().resolve("fraudcase/MongoFraudCaseSearchRepository.java"));
        String cursorPath = method(repository, "public Slice<FraudCaseDocument> searchSliceAfter", "private List<Criteria> criteria");

        assertThat(cursorPath)
                .contains("keysetCriteria(")
                .contains("query.limit(size + 1)")
                .doesNotContain(".skip(")
                .doesNotContain(".count(");
    }

    private String method(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start);
        return source.substring(start, end);
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
