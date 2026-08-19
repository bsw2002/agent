package org.suvia.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class RequestIdentityResolver {

    private final ApiSecurityProperties properties;

    public RequestIdentityResolver(ApiSecurityProperties properties) {
        this.properties = properties;
    }

    public RequestIdentity resolve(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String userId = jwtAuthentication.getToken().getSubject();
            String tenantId = jwtAuthentication.getToken().getClaimAsString(properties.getTenantClaim());
            if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
                throw new AccessDeniedException("The authenticated token is missing subject or tenant identity");
            }
            return new RequestIdentity(tenantId, userId);
        }

        if (properties.isEnabled()) {
            throw new AccessDeniedException("An authenticated JWT identity is required");
        }

        String developmentUser = authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : "local-developer";
        return new RequestIdentity("local-development", developmentUser);
    }
}
