package com.synapse.backend.services;

import com.synapse.backend.models.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final JdbcTemplate jdbcTemplate;

    public UserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public User getOrCreateUser(String sub, String email, String name) {

        if (sub == null || sub.isBlank() || sub.equals("guest") || sub.equals("anonymousUser")) {
            throw new IllegalArgumentException("A stable authenticated user subject is required");
        }

        UUID userId = UUID.randomUUID();

        // Use UPSERT (ON CONFLICT DO UPDATE) to prevent race conditions and duplicate key exceptions
        jdbcTemplate.update(
                "INSERT INTO users (id, sub, email, name, provider, created_at, last_login_at) " +
                        "VALUES (?, ?, ?, ?, 'cognito', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (sub) DO UPDATE SET " +
                        "last_login_at = CURRENT_TIMESTAMP, " +
                        "email = EXCLUDED.email, " +
                        "name = EXCLUDED.name",
                userId, sub, email, name
        );

        return findBySub(sub).orElseThrow(() -> new RuntimeException("User missing after upsert execution"));
    }

    public Optional<User> findBySub(String sub) {
        try {
            User user = jdbcTemplate.queryForObject(
                    "SELECT * FROM users WHERE sub = ?",
                    new UserRowMapper(),
                    sub
            );
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<User> findById(UUID userId) {
        try {
            User user = jdbcTemplate.queryForObject(
                    "SELECT * FROM users WHERE id = ?",
                    new UserRowMapper(),
                    userId
            );
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private void updateLastLogin(UUID userId) {
        jdbcTemplate.update(
                "UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?",
                userId
        );
    }

    private static class UserRowMapper implements RowMapper<User> {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            return User.builder()
                    .id(rs.getObject("id", UUID.class))
                    .sub(rs.getString("sub"))
                    .email(rs.getString("email"))
                    .name(rs.getString("name"))
                    .provider(rs.getString("provider"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .lastLoginAt(rs.getTimestamp("last_login_at") != null
                            ? rs.getTimestamp("last_login_at").toInstant()
                            : null)
                    .build();
        }
    }
}
