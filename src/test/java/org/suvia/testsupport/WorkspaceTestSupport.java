package org.suvia.testsupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

public abstract class WorkspaceTestSupport {

    protected Path temporaryDirectory;

    @BeforeEach
    void createWorkspaceTemporaryDirectory() throws IOException {
        temporaryDirectory = Path.of(
                "target",
                "test-workspaces",
                getClass().getSimpleName(),
                UUID.randomUUID().toString()
        ).toAbsolutePath().normalize();
        Files.createDirectories(temporaryDirectory);
    }

    @AfterEach
    void removeWorkspaceTemporaryDirectory() throws IOException {
        if (temporaryDirectory == null || !Files.exists(temporaryDirectory)) {
            return;
        }
        Path buildRoot = Path.of("target", "test-workspaces").toAbsolutePath().normalize();
        Path resolved = temporaryDirectory.toAbsolutePath().normalize();
        if (!resolved.startsWith(buildRoot)) {
            throw new IllegalStateException("Refusing to clean a test directory outside target/test-workspaces");
        }
        try (var paths = Files.walk(resolved)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to clean test path", e);
                }
            });
        }
    }
}
