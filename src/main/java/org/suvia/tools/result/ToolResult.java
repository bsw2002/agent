package org.suvia.tools.result;

public record ToolResult<T>(
        ToolStatus status,
        T data,
        ToolError error,
        boolean retryable
) {

    public static <T> ToolResult<T> success(T data) {
        return new ToolResult<>(ToolStatus.SUCCESS, data, null, false);
    }

    public static <T> ToolResult<T> error(String code, String message, boolean retryable) {
        return new ToolResult<>(ToolStatus.ERROR, null, new ToolError(code, message), retryable);
    }

    public static <T> ToolResult<T> denied(String code, String message) {
        return new ToolResult<>(ToolStatus.DENIED, null, new ToolError(code, message), false);
    }
}
