package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp40LocalPathHygieneTest {

    @Test
    void fdp40CodeAndDocsDoNotContainLocalDeveloperMachinePaths() throws Exception {
        try (Stream<Path> stream = Stream.concat(
                Files.walk(Path.of("src/test/java/com/frauddetection/alert/regulated")),
                Files.walk(Path.of("../docs"))
        )) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("Fdp40")
                            || path.getFileName().toString().contains("FDP-40"))
                    .toList()) {
                String content = Files.readString(file);
                assertThat(content)
                        .as(file.toString())
                        .doesNotContain("C:" + "/Users/")
                        .doesNotContain("C:" + "\\Users\\")
                        .doesNotContain("mp" + "ods");
            }
        }
    }
}
