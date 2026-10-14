package com.frauddetection.alert.governance.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class GovernanceArtifactReader {

    private static final int BUFFER_SIZE = 8_192;

    private final Consumer<Path> afterPreReadAttributes;

    public GovernanceArtifactReader() {
        this(path -> {
        });
    }

    public GovernanceArtifactReader(Consumer<Path> afterPreReadAttributes) {
        this.afterPreReadAttributes = Objects.requireNonNull(afterPreReadAttributes);
    }

    public Path resolveConfiguredFile(String baseDirValue, String pathValue, String expectedFilename) {
        if (isBlank(baseDirValue) || isBlank(pathValue) || containsParentTraversal(baseDirValue)
                || containsParentTraversal(pathValue)) {
            throw unavailable();
        }
        try {
            Path baseDir = Path.of(baseDirValue.trim()).toAbsolutePath().normalize();
            Path configuredPath = Path.of(pathValue.trim());
            Path resolved = (configuredPath.isAbsolute() ? configuredPath : baseDir.resolve(configuredPath))
                    .toAbsolutePath()
                    .normalize();
            if (!resolved.startsWith(baseDir)) {
                throw unavailable();
            }
            return requireFile(resolved, expectedFilename);
        } catch (InvalidPathException | SecurityException exception) {
            throw unavailable(exception);
        }
    }

    public Path resolveSibling(Path configuredFile, String expectedFilename) {
        Objects.requireNonNull(configuredFile);
        Path sibling = configuredFile.resolveSibling(expectedFilename).toAbsolutePath().normalize();
        if (!Objects.equals(sibling.getParent(), configuredFile.toAbsolutePath().normalize().getParent())) {
            throw unavailable();
        }
        return requireFile(sibling, expectedFilename);
    }

    public byte[] read(Path path, long maxSizeBytes) {
        if (maxSizeBytes < 0 || maxSizeBytes >= Integer.MAX_VALUE) {
            throw unavailable();
        }
        try {
            BasicFileAttributes before = regularAttributes(path);
            if (before.size() > maxSizeBytes) {
                throw unavailable();
            }
            afterPreReadAttributes.accept(path);
            byte[] payload;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                payload = readBounded(channel, maxSizeBytes);
            }
            BasicFileAttributes after = regularAttributes(path);
            if (changed(before, after) || payload.length != after.size()) {
                throw unavailable();
            }
            return payload;
        } catch (IOException | SecurityException exception) {
            throw unavailable(exception);
        }
    }

    public void requireExactDirectoryEntries(Path directory, Set<String> expectedEntries) {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(expectedEntries);
        assertNoSymlinkDirectory(directory);
        try (var entries = Files.list(directory)) {
            Set<String> actualEntries = entries
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
            if (!actualEntries.equals(expectedEntries)) {
                throw unavailable();
            }
        } catch (IOException | SecurityException exception) {
            throw unavailable(exception);
        }
    }

    private Path requireFile(Path path, String expectedFilename) {
        Path fileName = path.getFileName();
        if (fileName == null || !expectedFilename.equals(fileName.toString())) {
            throw unavailable();
        }
        assertNoSymlinkDirectory(path);
        try {
            regularAttributes(path);
            return path;
        } catch (IOException | SecurityException exception) {
            throw unavailable(exception);
        }
    }

    private void assertNoSymlinkDirectory(Path path) {
        Path parent = path.getParent();
        while (parent != null) {
            if (Files.isSymbolicLink(parent)) {
                throw unavailable();
            }
            parent = parent.getParent();
        }
    }

    private BasicFileAttributes regularAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile()) {
            throw new IOException("governance artifact must be a regular file");
        }
        return attributes;
    }

    private byte[] readBounded(FileChannel channel, long maxSizeBytes) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream((int) Math.min(channel.size(), BUFFER_SIZE));
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        long total = 0;
        while (channel.read(buffer) != -1) {
            buffer.flip();
            total += buffer.remaining();
            if (total > maxSizeBytes) {
                throw new IOException("governance artifact exceeds maximum size");
            }
            payload.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
            buffer.clear();
        }
        return payload.toByteArray();
    }

    private boolean changed(BasicFileAttributes before, BasicFileAttributes after) {
        return !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsParentTraversal(String value) {
        try {
            for (Path part : Path.of(value.trim())) {
                if ("..".equals(part.toString())) {
                    return true;
                }
            }
            return false;
        } catch (InvalidPathException exception) {
            return true;
        }
    }

    private GovernanceArtifactReadException unavailable() {
        return new GovernanceArtifactReadException("Governance artifact unavailable.");
    }

    private GovernanceArtifactReadException unavailable(Throwable cause) {
        return new GovernanceArtifactReadException("Governance artifact unavailable.", cause);
    }
}
