package org.suvia.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.suvia.tools.result.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TerminalOperationTool {

    private final boolean enabled;
    private final Set<String> allowedExecutables;
    private final Path workingDirectory;
    private final Duration timeout;
    private final int maxOutputChars;

    public TerminalOperationTool(
            boolean enabled,
            List<String> allowedExecutables,
            Path workingDirectory,
            Duration timeout,
            int maxOutputChars
    ) {
        this.enabled = enabled;
        this.allowedExecutables = allowedExecutables.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.timeout = timeout;
        this.maxOutputChars = Math.max(1, maxOutputChars);
    }

    @Tool(description = "Execute an explicitly allowlisted program without invoking a command shell")
    public ToolResult<Map<String, Object>> executeTerminalCommand(
            @ToolParam(description = "Allowlisted executable name, without a path") String executable,
            @ToolParam(description = "Individual command arguments. Shell operators are not interpreted.") List<String> arguments) {
        if (!enabled) {
            return ToolResult.denied("TERMINAL_DISABLED", "Terminal execution is disabled");
        }
        if (executable == null || executable.isBlank()) {
            return ToolResult.denied("EXECUTABLE_DENIED", "An allowlisted executable name is required");
        }

        Path executablePath;
        try {
            executablePath = Path.of(executable);
        } catch (RuntimeException e) {
            return ToolResult.denied("EXECUTABLE_DENIED", "The executable name is invalid");
        }
        if (executablePath.isAbsolute() || executablePath.getNameCount() != 1) {
            return ToolResult.denied("EXECUTABLE_DENIED", "Executable paths are not allowed");
        }
        if (!allowedExecutables.contains(executable.toLowerCase(Locale.ROOT))) {
            return ToolResult.denied("EXECUTABLE_DENIED", "The executable is not allowlisted");
        }

        List<String> command = new ArrayList<>();
        command.add(executable);
        if (arguments != null) {
            command.addAll(arguments);
        }

        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("COMMAND_TIMEOUT", "The command exceeded its time limit", true);
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < maxOutputChars) {
                    output.append(line).append("\n");
                }
            }
            boolean truncated = output.length() >= maxOutputChars;
            if (truncated) {
                output.setLength(maxOutputChars);
            }
            return ToolResult.success(Map.of(
                    "exitCode", process.exitValue(),
                    "output", output.toString(),
                    "truncated", truncated
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("COMMAND_INTERRUPTED", "The command was interrupted", true);
        } catch (IOException | RuntimeException e) {
            return ToolResult.error("COMMAND_FAILED", "The command could not be executed", false);
        }
    }
}
