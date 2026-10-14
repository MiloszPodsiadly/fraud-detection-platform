package com.frauddetection.alert.governance.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceArtifactReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesAndReadsOnlyCanonicalContainedRegularFile() throws Exception {
        Path artifact = tempDir.resolve("current.json");
        Files.writeString(artifact, "payload");
        GovernanceArtifactReader reader = new GovernanceArtifactReader();

        Path resolved = reader.resolveConfiguredFile(tempDir.toString(), "current.json", "current.json");

        assertThat(reader.read(resolved, 64)).isEqualTo("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void rejectsTraversalWrongFilenameAndOversizedPayload() throws Exception {
        Path artifact = tempDir.resolve("current.json");
        Files.writeString(artifact, "payload");
        GovernanceArtifactReader reader = new GovernanceArtifactReader();

        assertThatThrownBy(() -> reader.resolveConfiguredFile(tempDir.toString(), "../current.json", "current.json"))
                .isInstanceOf(GovernanceArtifactReadException.class);
        assertThatThrownBy(() -> reader.resolveConfiguredFile(tempDir.toString(), artifact.toString(), "other.json"))
                .isInstanceOf(GovernanceArtifactReadException.class);
        assertThatThrownBy(() -> reader.read(artifact, 2))
                .isInstanceOf(GovernanceArtifactReadException.class);
    }

    @Test
    void rejectsReplacementBetweenMetadataCheckAndRead() throws Exception {
        Path artifact = tempDir.resolve("current.json");
        Files.writeString(artifact, "payload");
        GovernanceArtifactReader reader = new GovernanceArtifactReader(path -> {
            try {
                Files.delete(path);
                Files.writeString(path, "payload");
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 60_000L));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertThatThrownBy(() -> reader.read(artifact, 64))
                .isInstanceOf(GovernanceArtifactReadException.class);
    }
}
