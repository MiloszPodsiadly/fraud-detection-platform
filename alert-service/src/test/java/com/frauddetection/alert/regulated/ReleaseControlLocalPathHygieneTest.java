package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseControlLocalPathHygieneTest {

    @Test
    void releaseControlCodeAndDocsDoNotContainLocalDeveloperMachinePaths() throws Exception {
        try (Stream<Path> stream = Stream.concat(
                Files.walk(Path.of("src/test/java/com/frauddetection/alert/regulated")),
                Files.walk(Path.of("../docs"))
        )) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(ReleaseControlLocalPathHygieneTest::isReleaseControlFile)
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

    private static boolean isReleaseControlFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith("ReleaseControl")
                || fileName.startsWith("ReleaseEvidence")
                || fileName.startsWith("ReleaseManifest")
                || fileName.contains("fdp_40");
    }
}
