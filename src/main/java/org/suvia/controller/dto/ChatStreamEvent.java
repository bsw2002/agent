package org.suvia.controller.dto;

public record ChatStreamEvent(String type, String chatId, String content) {
}
