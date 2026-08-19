package org.suvia.tools.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class SafeUrlPolicy {

    private final Set<String> allowedDomains;

    public SafeUrlPolicy(List<String> allowedDomains) {
        this.allowedDomains = allowedDomains == null
                ? Set.of()
                : allowedDomains.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public URI validate(String rawUrl) {
        final URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException | NullPointerException e) {
            throw new ToolPolicyViolationException("INVALID_URL", "The URL is invalid");
        }

        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new ToolPolicyViolationException("URL_SCHEME_DENIED", "Only HTTP and HTTPS URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new ToolPolicyViolationException("URL_CREDENTIALS_DENIED", "Credentials in URLs are not allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ToolPolicyViolationException("INVALID_URL_HOST", "The URL must contain a valid host");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!allowedDomains.isEmpty() && allowedDomains.stream().noneMatch(
                domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain)
        )) {
            throw new ToolPolicyViolationException("DOMAIN_DENIED", "The URL host is not in the configured allowlist");
        }

        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ToolPolicyViolationException("HOST_RESOLUTION_FAILED", "The URL host could not be resolved");
        }

        for (InetAddress address : addresses) {
            if (isDeniedAddress(address)) {
                throw new ToolPolicyViolationException(
                        "PRIVATE_NETWORK_DENIED",
                        "URLs resolving to local or private networks are not allowed"
                );
            }
        }
        return uri.normalize();
    }

    private boolean isDeniedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
