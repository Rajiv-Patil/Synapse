package com.synapse.backend.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("legacy")
public class LegacyDatasetSchemaMigration {
    @Bean
    ApplicationRunner addDatasetOwnerSubject(JdbcTemplate jdbcTemplate) {
        return args -> {
            jdbcTemplate.execute("ALTER TABLE csv_documents ADD COLUMN IF NOT EXISTS owner_sub VARCHAR(255)");
            jdbcTemplate.update("UPDATE csv_documents d SET owner_sub = u.sub " +
                    "FROM conversations c JOIN users u ON u.id = c.user_id " +
                    "WHERE d.conversation_id = c.id AND d.owner_sub IS NULL");
            jdbcTemplate.execute("ALTER TABLE csv_documents ALTER COLUMN owner_sub SET NOT NULL");
        };
    }
}
