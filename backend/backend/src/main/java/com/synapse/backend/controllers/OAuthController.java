package com.synapse.backend.controllers;

import com.synapse.backend.models.User;
import com.synapse.backend.services.UserService;
import com.synapse.backend.services.RequestIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@Profile({"identity", "legacy"})
public class OAuthController {

    private final UserService userService;
    private final RequestIdentity requestIdentity;

    public OAuthController(UserService userService, RequestIdentity requestIdentity) {
        this.userService = userService;
        this.requestIdentity = requestIdentity;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() ||
                authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).body(Map.of("authenticated", false));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", true);
        response.put("name", authentication.getName());
        response.put("roles", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList()));

        if (authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            String sub = oidcUser.getSubject();
            String email = oidcUser.getEmail();
            Object nameClaim = oidcUser.getAttributes().get("name");
            String name = nameClaim instanceof String value && !value.isBlank()
                    ? value : (email != null && !email.isBlank() ? email : sub);

            response.put("name", name);
            response.put("email", email);
            response.put("sub", sub);

            // Get or create user in database
            User user = userService.getOrCreateUser(sub, email, name);
            response.put("userId", user.getId());
            response.put("userId", user.getId());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/internal/auth-context")
    public ResponseEntity<Void> authContext(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() ||
                authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var identity = requestIdentity.resolve(authentication, null, null);
        return ResponseEntity.noContent()
                .header("X-Authenticated-Sub", identity.subject())
                .header("X-Authenticated-User-Id", identity.userId().toString())
                .build();
    }

    @GetMapping("/admin/greetMe")
    public ResponseEntity<?> adminGreet(Authentication authentication) {
        return ResponseEntity.ok(Map.of(
                "message", "Welcome to Admin Dashboard!",
                "user", authentication.getName(),
                "role", "ROLE_ADMIN"
        ));
    }

    @GetMapping("/user/greetMe")
    public ResponseEntity<?> userGreet(Authentication authentication) {
        return ResponseEntity.ok(Map.of(
                "message", "Welcome User!",
                "user", authentication.getName(),
                "role", "ROLE_USER"
        ));
    }
}
