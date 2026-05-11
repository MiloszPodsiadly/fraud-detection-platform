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

    @Test
    void productionQueryServiceConstructorShouldUseConfiguredCursorSecret() throws IOException {
        String service = Files.readString(sourceRoot().resolve("service/FraudCaseQueryService.java"))
                .replace("\r\n", "\n");
        String productionConstructor = method(
                service,
                "@Autowired",
                "    FraudCaseQueryService(\n            FraudCaseRepository fraudCaseRepository,\n            FraudCaseAuditRepository auditRepository,\n            FraudCaseSearchRepository searchRepository,\n            FraudCaseResponseMapper responseMapper,\n            Clock clock,"
        );
        String properties = Files.readString(sourceRoot().resolve("fraudcase/FraudCaseWorkQueueProperties.java"));

        assertThat(productionConstructor)
                .contains("FraudCaseWorkQueueProperties workQueueProperties")
                .contains("new FraudCaseWorkQueueCursorCodec(workQueueProperties.cursorSigningSecret())")
                .doesNotContain("localDefault()");
        assertThat(properties)
                .contains("Test-only constructor")
                .contains("Production binding must use FraudCaseWorkQueueProperties(Duration, String)");
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
