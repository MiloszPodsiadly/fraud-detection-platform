package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class HiddenUnicodeDocumentationSafetyTest {

    private static final Map<Integer, String> FORBIDDEN_CODE_POINTS = Map.ofEntries(
            Map.entry(0x202A, "LEFT-TO-RIGHT EMBEDDING"),
            Map.entry(0x202B, "RIGHT-TO-LEFT EMBEDDING"),
            Map.entry(0x202C, "POP DIRECTIONAL FORMATTING"),
            Map.entry(0x202D, "LEFT-TO-RIGHT OVERRIDE"),
            Map.entry(0x202E, "RIGHT-TO-LEFT OVERRIDE"),
            Map.entry(0x2066, "LEFT-TO-RIGHT ISOLATE"),
            Map.entry(0x2067, "RIGHT-TO-LEFT ISOLATE"),
            Map.entry(0x2068, "FIRST STRONG ISOLATE"),
            Map.entry(0x2069, "POP DIRECTIONAL ISOLATE"),
            Map.entry(0x200B, "ZERO WIDTH SPACE"),
            Map.entry(0x200C, "ZERO WIDTH NON-JOINER"),
            Map.entry(0x200D, "ZERO WIDTH JOINER"),
            Map.entry(0xFEFF, "ZERO WIDTH NO-BREAK SPACE")
    );

    @Test
    void docsConfigsAndCiDoNotContainHiddenUnicodeOrBidiControls() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path path : scannedFiles()) {
            String[] lines = Files.readString(path).split("\\R", -1);
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                int[] codePoints = lines[lineIndex].codePoints().toArray();
                for (int codePoint : codePoints) {
                    if (FORBIDDEN_CODE_POINTS.containsKey(codePoint)) {
                        violations.add("%s:%d: U+%04X %s".formatted(
                                path,
                                lineIndex + 1,
                                codePoint,
                                FORBIDDEN_CODE_POINTS.get(codePoint)
                        ));
                    }
                }
            }
        }

        assertThat(violations)
                .as("Hidden Unicode / bidi controls must not appear in docs, configs, or CI files")
                .isEmpty();
    }

    private List<Path> scannedFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        files.add(Path.of("../README.md"));
        files.addAll(walk(Path.of("../docs"), ".md", ".yaml", ".yml"));
        files.addAll(walk(Path.of("../.github"), ".yaml", ".yml", ".md"));
        return files;
    }

    private List<Path> walk(Path root, String... suffixes) throws Exception {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String value = path.toString();
                        for (String suffix : suffixes) {
                            if (value.endsWith(suffix)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .toList();
        }
    }
}
