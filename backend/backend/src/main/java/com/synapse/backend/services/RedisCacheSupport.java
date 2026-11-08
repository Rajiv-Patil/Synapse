package com.synapse.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Fail-open cache-aside wrapper: Redis is an accelerator, never a dependency.
 * Any read/write error is logged and swallowed so callers always fall back to their source of truth.
 */
@Component
@Profile({"conversation", "ai", "dataset", "legacy"})
public class RedisCacheSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheSupport.class);

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisCacheSupport(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
        } catch (Exception e) {
            log.warn("Redis read failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis write failed for key {}: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis evict failed for key {}: {}", key, e.getMessage());
        }
    }
}
