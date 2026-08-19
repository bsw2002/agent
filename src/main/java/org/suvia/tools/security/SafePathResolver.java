package org.suvia.tools.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SafePathResolver {

    private final Path root;

    public SafePathResolver(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Tool workspace root is required");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new ToolPolicyViolationException("INVALID_PATH", "A non-empty relative path is required");
        }

        Path supplied;
        try {
            supplied = Path.of(userPath);
        } catch (RuntimeException e) {
            throw new ToolPolicyViolationException("INVALID_PATH", "The supplied path is invalid");
        }

        if (supplied.isAbsolute()) {
            throw new ToolPolicyViolationException("PATH_OUTSIDE_WORKSPACE", "Absolute paths are not allowed");
        }

        Path candidate = root.resolve(supplied).normalize();
        if (!candidate.startsWith(root)) {
            throw new ToolPolicyViolationException("PATH_OUTSIDE_WORKSPACE", "The path escapes the tool workspace");
        }

        rejectSymbolicLinkTraversal(candidate);
        return candidate;
    }

    private void rejectSymbolicLinkTraversal(Path candidate) {
        Path current = root;
        Path relative = root.relativize(candidate);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current) && Files.isSymbolicLink(current)) {
                throw new ToolPolicyViolationException(
                        "SYMLINK_NOT_ALLOWED",
                        "Symbolic links are not allowed in tool paths"
                );
            }
        }

        try {
            if (Files.exists(root) && Files.isSymbolicLink(root)) {
                throw new ToolPolicyViolationException(
                        "SYMLINK_NOT_ALLOWED",
                        "The tool workspace cannot be a symbolic link"
                );
            }
        } catch (SecurityException e) {
            throw new ToolPolicyViolationException("PATH_CHECK_FAILED", "Unable to validate the tool path");
        }
    }

    public void createRoot() throws IOException {
        Files.createDirectories(root);
    }
}
