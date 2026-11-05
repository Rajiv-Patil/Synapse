package com.synapse.backend.services;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RequestIdentity {
    private final ObjectProvider<UserService> userService;

    public RequestIdentity(ObjectProvider<UserService> userService) {
        this.userService = userService;
    }

    public Identity resolve(Authentication authentication, String forwardedSub, String forwardedUserId) {
        if (forwardedSub != null && !forwardedSub.isBlank() &&
                forwardedUserId != null && !forwardedUserId.isBlank()) {
            return new Identity(UUID.fromString(forwardedUserId), forwardedSub, null, null);
        }

        if (authentication != null && authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            String sub = oidcUser.getSubject();
            String email = oidcUser.getEmail();
            Object nameClaim = oidcUser.getAttributes().get("name");
            String name = nameClaim instanceof String value && !value.isBlank()
                    ? value : (email != null && !email.isBlank() ? email : sub);
            UserService users = userService.getIfAvailable();
            if (users == null) throw new IllegalStateException("Identity service is not available in this service profile");
            var user = users.getOrCreateUser(sub, email, name);
            return new Identity(user.getId(), sub, email, name);
        }

        if (authentication != null && authentication.isAuthenticated() &&
                forwardedUserId != null && !forwardedUserId.isBlank()) {
            String sub = forwardedSub != null && !forwardedSub.isBlank() ? forwardedSub : authentication.getName();
            return new Identity(UUID.fromString(forwardedUserId), sub, null, null);
        }
        throw new IllegalArgumentException("Authenticated user identity is missing");
    }

    public record Identity(UUID userId, String subject, String email, String name) {
        public void applyHeaders(org.springframework.http.HttpHeaders headers) {
            headers.set("X-Authenticated-Sub", subject);
            headers.set("X-Authenticated-User-Id", userId.toString());
        }
    }
}
