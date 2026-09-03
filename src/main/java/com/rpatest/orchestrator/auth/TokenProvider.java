package com.rpatest.orchestrator.auth;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/** Потокобезопасное in-memory хранилище текущего JWT токена оркестратора. Никогда не персистится. */
@Component
public class TokenProvider {

    private final ReentrantLock lock = new ReentrantLock();
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public boolean hasValidToken() {
        return token != null && Instant.now().isBefore(expiresAt.minusSeconds(SAFETY_MARGIN_SECONDS));
    }

    public String getToken() {
        return token;
    }

    public void update(String newToken, Instant newExpiresAt) {
        lock.lock();
        try {
            this.token = newToken;
            this.expiresAt = newExpiresAt;
        } finally {
            lock.unlock();
        }
    }

    public void invalidate() {
        lock.lock();
        try {
            this.token = null;
            this.expiresAt = Instant.EPOCH;
        } finally {
            lock.unlock();
        }
    }

    public ReentrantLock getLock() {
        return lock;
    }

    private static final long SAFETY_MARGIN_SECONDS = 30;
}
