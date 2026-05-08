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
            Map.entry(0xFEFF, "ZERO WIDTH NO-BREAK SPACE"),
            Map.entry(0x00A0, "NO-BREAK SPACE")
    );

    @Test
    void docsConfigsAndCiDoNotContainHiddenUnicodeOrBidiControls() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path path : scannedFiles()) {
            String[] lines = Files.readString(path).split("\\R", -1);
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                String line = lines[lineIndex];
                for (int offset = 0, column = 1; offset < line.length(); column++) {
                    int codePoint = line.codePointAt(offset);
                    if (isForbidden(codePoint)) {
                        violations.add("%s:%d:%d: U+%04X %s context=\"%s\"".formatted(
                                DocumentationTestSupport.relativeToRepository(path),
                                lineIndex + 1,
                                column,
                                codePoint,
                                codePointName(codePoint),
                                escapedContext(line, offset)
                        ));
                    }
                    offset += Character.charCount(codePoint);
                }
            }
        }

        assertThat(violations)
                .as("Hidden Unicode / bidi controls must not appear in docs, configs, or CI files")
                .isEmpty();
    }

    private boolean isForbidden(int codePoint) {
        if (FORBIDDEN_CODE_POINTS.containsKey(codePoint)) {
            return true;
        }
        if (Character.getType(codePoint) == Character.FORMAT) {
            return true;
        }
        return Character.isISOControl(codePoint)
                && codePoint != '\n'
                && codePoint != '\r'
                && codePoint != '\t';
    }

    private String codePointName(int codePoint) {
        return FORBIDDEN_CODE_POINTS.getOrDefault(codePoint, Character.getName(codePoint));
    }

    private String escapedContext(String line, int offset) {
        int start = Math.max(0, offset - 20);
        int end = Math.min(line.length(), offset + 21);
        String context = line.substring(start, end);
        StringBuilder escaped = new StringBuilder();
        context.codePoints().forEach(codePoint -> {
            if (codePoint < 0x20 || codePoint > 0x7E || Character.getType(codePoint) == Character.FORMAT) {
                escaped.append("\\u%04X".formatted(codePoint));
            } else {
                escaped.appendCodePoint(codePoint);
            }
        });
        return escaped.toString();
    }

    private List<Path> scannedFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        Path repositoryRoot = DocumentationTestSupport.repositoryRoot();
        files.add(repositoryRoot.resolve("README.md"));
        files.addAll(walk(repositoryRoot.resolve("docs"), ".md", ".yaml", ".yml"));
        files.addAll(walk(repositoryRoot.resolve(".github"), ".yaml", ".yml", ".md"));
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
