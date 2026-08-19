package org.suvia.memory;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class MemoryWritePolicy {

    private static final int MAX_CONTENT_CHARACTERS = 4000;
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(api[_-]?key|access[_-]?key|secret[_-]?key|password)\\b\\s*[:=]"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")
    );

    public String validateAndNormalize(
            String content,
            MemoryKind kind,
            MemorySource source,
            double confidence
    ) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }
        String normalized = content.strip();
        if (normalized.length() > MAX_CONTENT_CHARACTERS) {
            throw new IllegalArgumentException("Memory content exceeds 4000 characters");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Memory confidence must be between 0 and 1");
        }
        if (source == MemorySource.TOOL_OBSERVATION) {
            throw new IllegalArgumentException("Raw tool observations cannot be persisted as durable memory");
        }
        if (kind == MemoryKind.PROCEDURAL && source != MemorySource.SYSTEM_POLICY) {
            throw new IllegalArgumentException("Only trusted system policy may create procedural memory");
        }
        if (source == MemorySource.AGENT_CONFIRMED && confidence < 0.8) {
            throw new IllegalArgumentException("Agent-confirmed memory requires confidence of at least 0.8");
        }
        if (containsCredential(normalized)) {
            throw new IllegalArgumentException("Credentials and secrets must not be stored in durable memory");
        }
        return normalized;
    }

    private boolean containsCredential(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return SECRET_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}
