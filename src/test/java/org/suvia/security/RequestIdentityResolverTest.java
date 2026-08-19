package org.suvia.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestIdentityResolverTest {

    @Test
    void usesAnExplicitLocalIdentityOnlyWhenSecurityIsDisabled() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setEnabled(false);
        RequestIdentityResolver resolver = new RequestIdentityResolver(properties);

        RequestIdentity identity = resolver.resolve(null);

        assertEquals("local-development", identity.tenantId());
        assertEquals("local-developer", identity.userId());
    }

    @Test
    void refusesAnonymousIdentityWhenSecurityIsEnabled() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setEnabled(true);
        RequestIdentityResolver resolver = new RequestIdentityResolver(properties);

        assertThrows(AccessDeniedException.class, () -> resolver.resolve(null));
    }
}
