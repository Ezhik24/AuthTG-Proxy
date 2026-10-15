package org.ezhik.authTGVelocity;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AuthSessionStoreTest {
    @Test
    void sessionExpiresAndIsRemoved() {
        MutableClock clock = new MutableClock();
        AuthSessionStore store = new AuthSessionStore(Duration.ofSeconds(30), clock);
        UUID uuid = UUID.randomUUID();
        store.start(uuid);
        assertTrue(store.contains(uuid));
        clock.advance(Duration.ofSeconds(31));
        assertFalse(store.contains(uuid));
        assertEquals(0, store.size());
    }

    @Test
    void successfulAuthorizationRemovesSession() {
        AuthSessionStore store = new AuthSessionStore(Duration.ofMinutes(1));
        UUID uuid = UUID.randomUUID();
        store.start(uuid);
        store.finish(uuid);
        assertFalse(store.contains(uuid));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}





