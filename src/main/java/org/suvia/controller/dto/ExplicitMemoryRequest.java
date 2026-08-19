package org.suvia.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.suvia.memory.MemoryScope;
import org.suvia.memory.MemorySensitivity;

public record ExplicitMemoryRequest(
        @NotBlank @Size(max = 4000) String content,
        MemoryScope scope,
        @Size(max = 128) String chatId,
        MemorySensitivity sensitivity
) {
}
