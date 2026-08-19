package org.suvia.tools.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "suvia.tools.security")
public class ToolSecurityProperties {

    private String workspaceDirectory = "tmp";
    private int networkTimeoutSeconds = 10;
    private long maxDownloadBytes = 25L * 1024L * 1024L;
    private int maxWebContentChars = 100_000;
    private long maxTextFileBytes = 2L * 1024L * 1024L;
    private List<String> allowedDomains = new ArrayList<>();
    private final Terminal terminal = new Terminal();

    public String getWorkspaceDirectory() {
        return workspaceDirectory;
    }

    public void setWorkspaceDirectory(String workspaceDirectory) {
        this.workspaceDirectory = workspaceDirectory;
    }

    public int getNetworkTimeoutSeconds() {
        return networkTimeoutSeconds;
    }

    public void setNetworkTimeoutSeconds(int networkTimeoutSeconds) {
        this.networkTimeoutSeconds = networkTimeoutSeconds;
    }

    public long getMaxDownloadBytes() {
        return maxDownloadBytes;
    }

    public void setMaxDownloadBytes(long maxDownloadBytes) {
        this.maxDownloadBytes = maxDownloadBytes;
    }

    public int getMaxWebContentChars() {
        return maxWebContentChars;
    }

    public void setMaxWebContentChars(int maxWebContentChars) {
        this.maxWebContentChars = maxWebContentChars;
    }

    public long getMaxTextFileBytes() {
        return maxTextFileBytes;
    }

    public void setMaxTextFileBytes(long maxTextFileBytes) {
        this.maxTextFileBytes = maxTextFileBytes;
    }

    public List<String> getAllowedDomains() {
        return allowedDomains;
    }

    public void setAllowedDomains(List<String> allowedDomains) {
        this.allowedDomains = allowedDomains == null ? new ArrayList<>() : new ArrayList<>(allowedDomains);
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public static class Terminal {
        private boolean enabled = false;
        private int timeoutSeconds = 20;
        private int maxOutputChars = 50_000;
        private List<String> allowedExecutables = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        public void setMaxOutputChars(int maxOutputChars) {
            this.maxOutputChars = maxOutputChars;
        }

        public List<String> getAllowedExecutables() {
            return allowedExecutables;
        }

        public void setAllowedExecutables(List<String> allowedExecutables) {
            this.allowedExecutables = allowedExecutables == null
                    ? new ArrayList<>()
                    : new ArrayList<>(allowedExecutables);
        }
    }
}
