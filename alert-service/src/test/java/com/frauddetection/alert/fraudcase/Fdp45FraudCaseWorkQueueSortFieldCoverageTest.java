package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Indexed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueSortFieldCoverageTest {

    private static final Set<String> EXPECTED_SORT_FIELDS = Set.of(
            "createdAt",
            "updatedAt",
            "priority",
            "riskLevel",
            "caseNumber"
    );

    @Test
    void everyAllowedSortFieldShouldHaveCursorIndexOpenApiAndDocsCoverage() throws Exception {
        assertThat(FraudCaseReadQueryPolicy.SORT_FIELDS).containsExactlyInAnyOrderElementsOf(EXPECTED_SORT_FIELDS);

        FraudCaseDocument document = documentWithEveryCursorSortValue();
        FraudCaseWorkQueueCursorCodec codec = FraudCaseWorkQueueCursorCodec.localDefault();
        MongoFraudCaseSearchRepository repository = new MongoFraudCaseSearchRepository(null);
        String docs = Files.readString(projectRoot().resolve("docs/fdp/fdp_45_work_queue_readiness.md"));
        String openApi = Files.readString(projectRoot().resolve("docs/openapi/alert_service.openapi.yaml"));

        for (String field : FraudCaseReadQueryPolicy.SORT_FIELDS) {
            String encoded = codec.encode(Sort.Order.asc(field), document, "query-hash");
            assertThat(encoded).as("cursor codec must encode %s", field).isNotBlank();
            assertThat(cursorValue(repository, field)).as("repository cursor value must decode %s", field).isNotNull();
            assertThat(FraudCaseDocument.class.getDeclaredField(field).isAnnotationPresent(Indexed.class))
                    .as("%s must be directly indexed or receive an explicit documented exception", field)
                    .isTrue();
            assertThat(openApi).as("OpenAPI must document %s", field).contains(field);
            assertThat(docs).as("docs must mention %s sort semantics", field).contains(field);
        }
    }

    private Object cursorValue(MongoFraudCaseSearchRepository repository, String field) {
        return switch (field) {
            case "createdAt", "updatedAt" -> repository.cursorValue(field, "2026-05-10T10:00:00Z");
            case "priority" -> repository.cursorValue(field, FraudCasePriority.HIGH.name());
            case "riskLevel" -> repository.cursorValue(field, RiskLevel.CRITICAL.name());
            case "caseNumber" -> repository.cursorValue(field, "FC-100");
            default -> null;
        };
    }

    private FraudCaseDocument documentWithEveryCursorSortValue() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-100");
        document.setCaseNumber("FC-100");
        document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-10T11:00:00Z"));
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        return document;
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
