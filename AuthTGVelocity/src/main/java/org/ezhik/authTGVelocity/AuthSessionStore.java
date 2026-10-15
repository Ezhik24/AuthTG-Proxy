package org.ezhik.authTGVelocity;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AuthSessionStore {
    private final Map<UUID, Long> expiresAt = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile long ttlMillis;

    AuthSessionStore(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    AuthSessionStore(Duration ttl, Clock clock) {
        this.clock = clock;
        setTtl(ttl);
    }

    void setTtl(Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Authorization TTL must be positive");
        }
        ttlMillis = ttl.toMillis();
    }

    void start(UUID uuid) {
        expiresAt.put(uuid, clock.millis() + ttlMillis);
    }

    void finish(UUID uuid) {
        expiresAt.remove(uuid);
    }

    boolean contains(UUID uuid) {
        Long expiration = expiresAt.get(uuid);
        if (expiration == null) return false;
        if (expiration > clock.millis()) return true;
        expiresAt.remove(uuid, expiration);
        return false;
    }

    int cleanup() {
        long now = clock.millis();
        int before = expiresAt.size();
        expiresAt.entrySet().removeIf(entry -> entry.getValue() <= now);
        return before - expiresAt.size();
    }

    int size() {
        cleanup();
        return expiresAt.size();
    }
}

