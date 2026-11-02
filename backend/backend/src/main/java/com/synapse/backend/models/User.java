package com.synapse.backend.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private UUID id;
    private String sub;
    private String email;
    private String name;
    private String provider;
    private Instant createdAt;
    private Instant lastLoginAt;
}
