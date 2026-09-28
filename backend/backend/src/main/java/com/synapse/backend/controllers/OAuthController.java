package com.synapse.backend.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class OAuthController {

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("authenticated", false));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", true);
        response.put("name", authentication.getName());
        response.put("roles", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList()));

        if (authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            response.put("email", oidcUser.getAttribute("email"));
            response.put("attributes", oidcUser.getAttributes());
        }

        return ResponseEntity.ok(response);
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