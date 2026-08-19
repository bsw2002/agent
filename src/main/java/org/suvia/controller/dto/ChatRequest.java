package org.suvia.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 20_000) String message,
        @Size(max = 128) String chatId
) {
}
