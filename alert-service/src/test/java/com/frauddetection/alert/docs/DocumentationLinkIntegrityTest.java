package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationLinkIntegrityTest {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[[^]]*]\\(([^)]+)\\)");

    @Test
    void relativeMarkdownLinksPointToExistingFiles() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Path doc : markdownDocs()) {
            String content = Files.readString(doc);
            var matcher = MARKDOWN_LINK.matcher(content);
            while (matcher.find()) {
                String target = matcher.group(1).trim();
                if (shouldIgnore(target)) {
                    continue;
                }
                String filePart = target.split("#", 2)[0];
                if (filePart.isBlank()) {
                    continue;
                }
                Path resolved = doc.getParent().resolve(URLDecoder.decode(filePart, StandardCharsets.UTF_8)).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(doc + " -> " + target);
                }
            }
        }
        writeLinkReport(broken);
        assertThat(broken).as("Broken documentation links").isEmpty();
    }

    private List<Path> markdownDocs() throws Exception {
        try (Stream<Path> stream = Files.walk(Path.of("../docs"))) {
            List<Path> docs = stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .toList();
            ArrayList<Path> all = new ArrayList<>(docs);
            all.add(Path.of("../README.md"));
            return all;
        }
    }

    private boolean shouldIgnore(String target) {
        return target.startsWith("http://")
                || target.startsWith("https://")
                || target.startsWith("mailto:")
                || target.startsWith("app://")
                || target.startsWith("#")
                || target.startsWith("file:");
    }

    private void writeLinkReport(List<String> broken) throws Exception {
        Path report = Path.of("target/docs-safety/link-report.md");
        Files.createDirectories(report.getParent());
        if (broken.isEmpty()) {
            Files.writeString(report, "# Documentation Link Report\n\nNo broken relative links found.\n");
            return;
        }
        StringBuilder builder = new StringBuilder("# Documentation Link Report\n\n");
        for (String entry : broken) {
            builder.append("- ").append(entry).append('\n');
        }
        Files.writeString(report, builder.toString());
    }
}

