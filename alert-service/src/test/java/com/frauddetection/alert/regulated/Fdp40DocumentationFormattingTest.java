package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp40DocumentationFormattingTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void fdp40DocsJsonAndScriptsAreAuditable() throws Exception {
        try (Stream<Path> stream = Files.walk(Path.of("../docs"))) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("FDP-40"))
                    .toList()) {
                String content = Files.readString(file);
                assertThat(content).doesNotContain("enablemenance");
                assertThat(content).doesNotContain("PLACEHOLDER");
                assertThat(content).doesNotContain("TO_BE_FILLED");
                if (file.toString().endsWith(".md")) {
                    assertThat(content).contains("\n");
                    for (String line : content.split("\\R")) {
                        if (!line.startsWith("http") && !line.startsWith("```")) {
                            assertThat(line.length())
                                    .as("Long audit-unfriendly line in " + file + ": " + line)
                                    .isLessThanOrEqualTo(240);
                        }
                    }
                }
                if (file.toString().endsWith(".json")) {
                    OBJECT_MAPPER.readTree(content);
                    assertThat(content).contains("\n  ");
                }
            }
        }
        for (String scriptName : Files.list(Path.of("../scripts"))
                .filter(path -> path.getFileName().toString().startsWith("fdp40-"))
                .map(path -> path.getFileName().toString())
                .toList()) {
            assertThat(Files.readString(Path.of("../scripts", scriptName))).contains("set -euo pipefail");
        }
    }
}
