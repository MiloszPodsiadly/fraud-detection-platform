package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueOpenApiContractTest {

    @Test
    void oldListAndWorkQueueOpenApiContractsShouldMatchImplementedParamsAndSchemas() throws Exception {
        String openApi = Files.readString(projectRoot().resolve("docs/openapi/alert_service.openapi.yaml"));
        String oldEndpoint = section(openApi, "  /api/v1/fraud-cases:", "    post:");
        String workQueueEndpoint = section(openApi, "  /api/v1/fraud-cases/work-queue:", "  /api/v1/fraud-cases/{caseId}:");
        String workQueueSchema = section(openApi, "    FraudCaseWorkQueueSlice:", "    FraudCaseSlaStatus:");

        assertThat(parameterNames(oldEndpoint))
                .containsExactly("page", "size", "status", "assignee", "priority", "riskLevel", "createdFrom", "createdTo", "linkedAlertId");
        assertThat(oldEndpoint)
                .contains("$ref: \"#/components/schemas/FraudCaseSummaryPage\"")
                .doesNotContain("assignedInvestigatorId")
                .doesNotContain("updatedFrom")
                .doesNotContain("updatedTo")
                .doesNotContain("name: sort");
        assertThat(parameterNames(workQueueEndpoint))
                .containsExactly("page", "size", "sort", "cursor", "status", "assignee", "assignedInvestigatorId", "priority",
                        "riskLevel", "createdFrom", "createdTo", "updatedFrom", "updatedTo", "linkedAlertId");
        assertThat(workQueueEndpoint)
                .contains("$ref: \"#/components/schemas/FraudCaseWorkQueueSlice\"")
                .contains("Cursor/keyset pagination is recommended")
                .contains("Cursor values are opaque, signed, not encrypted")
                .contains("Cursor pagination is not snapshot isolation")
                .contains("Changing filters or sort with a cursor returns INVALID_CURSOR")
                .contains("INVALID_CURSOR")
                .contains("INVALID_CURSOR_PAGE_COMBINATION")
                .contains("maximum: 1000")
                .contains("maximum: 100");
        assertThat(workQueueSchema)
                .doesNotContain("totalElements")
                .doesNotContain("totalPages")
                .contains("hasNext")
                .contains("nextPage")
                .contains("nextCursor")
                .contains("sort");
    }

    private List<String> parameterNames(String section) {
        return section.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- name: "))
                .map(line -> line.substring("- name: ".length()))
                .toList();
    }

    private String section(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        return source.substring(startIndex, endIndex);
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
