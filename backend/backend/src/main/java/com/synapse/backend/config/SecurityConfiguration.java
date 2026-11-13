package com.synapse.backend.config;

import com.synapse.backend.handlers.LoginSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@Profile({"identity", "legacy"})
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    private final LoginSuccessHandler loginSuccessHandler;

    // Inject the LoginSuccessHandler
    public SecurityConfiguration(LoginSuccessHandler loginSuccessHandler) {
        this.loginSuccessHandler = loginSuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/static/**",
                                "/api/public",
                                "/api/auth/me",
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        .requestMatchers("/api/csv/**").authenticated()
                        .requestMatchers("/api/conversations/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/api/upload/**").authenticated()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/user/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().authenticated()
                )
                // Attach the success handler to the OAuth2 Login flow
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(loginSuccessHandler)
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof OAuth2AuthenticationException oauthException) {
                                log.warn("Cognito sign-in failed ({}): {}",
                                        oauthException.getError().getErrorCode(),
                                        oauthException.getError().getDescription());
                            } else {
                                log.warn("Cognito sign-in failed ({})", exception.getClass().getSimpleName());
                            }
                            response.sendRedirect("/login?error");
                        })
                );

        return http.build();
    }

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://127.0.0.1:5173,http://127.0.0.1:5174}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public GrantedAuthoritiesMapper userAuthoritiesMapper() {
        return (authorities) -> {
            Set<GrantedAuthority> mappedAuthorities = new HashSet<>();
            for (GrantedAuthority authority : authorities) {
                var attributes = authority instanceof OidcUserAuthority oidc
                        ? oidc.getAttributes()
                        : authority instanceof OAuth2UserAuthority oauth2 ? oauth2.getAttributes() : null;
                Object groups = attributes != null ? attributes.get("cognito:groups") : null;
                if (groups instanceof List<?> groupList) {
                    mappedAuthorities.addAll(groupList.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .collect(Collectors.toSet()));
                }
            }
            return mappedAuthorities;
        };
    }
}
