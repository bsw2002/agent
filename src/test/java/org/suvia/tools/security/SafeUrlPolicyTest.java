package org.suvia.tools.security;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeUrlPolicyTest {

    @Test
    void rejectsNonHttpSchemesAndLocalAddresses() {
        SafeUrlPolicy policy = new SafeUrlPolicy(List.of());

        assertEquals("URL_SCHEME_DENIED", assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.validate("file:///etc/passwd")
        ).getCode());
        assertEquals("PRIVATE_NETWORK_DENIED", assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.validate("http://127.0.0.1/admin")
        ).getCode());
        assertEquals("PRIVATE_NETWORK_DENIED", assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.validate("http://10.0.0.1/admin")
        ).getCode());
    }

    @Test
    void enforcesDomainAllowlist() {
        SafeUrlPolicy policy = new SafeUrlPolicy(List.of("example.com"));

        assertEquals("DOMAIN_DENIED", assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.validate("https://8.8.8.8/resource")
        ).getCode());
    }

    @Test
    void acceptsPublicHttpAddress() {
        SafeUrlPolicy policy = new SafeUrlPolicy(List.of());

        URI result = policy.validate("https://8.8.8.8/resource");

        assertEquals("https", result.getScheme());
    }
}
