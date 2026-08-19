package org.suvia.tools.security;

public class ToolPolicyViolationException extends RuntimeException {

    private final String code;

    public ToolPolicyViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
