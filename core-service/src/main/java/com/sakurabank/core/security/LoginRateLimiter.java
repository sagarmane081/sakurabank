package com.sakurabank.core.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private static final int MAX_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, Window> windows =
            new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isAllowed(
            String ipAddress,
            String username) {

        String key = key(ipAddress, username);
        Instant now = Instant.now(clock);

        Window window = windows.compute(
                key,
                (ignored, current) -> {

                    if (current == null ||
                            !now.isBefore(
                                    current.startedAt()
                                            .plus(WINDOW)
                            )) {

                        return new Window(now, 1);
                    }

                    return new Window(
                            current.startedAt(),
                            current.count() + 1
                    );
                }
        );

        return window.count() <= MAX_REQUESTS;
    }

    public void reset(
            String ipAddress,
            String username) {

        windows.remove(
                key(ipAddress, username)
        );
    }

    public long secondsUntilReset(
            String ipAddress,
            String username) {

        Window window =
                windows.get(
                        key(ipAddress, username)
                );

        if (window == null) {
            return 0;
        }

        Instant now = Instant.now(clock);

        Instant resetAt =
                window.startedAt()
                        .plus(WINDOW);

        if (!now.isBefore(resetAt)) {

            windows.remove(
                    key(ipAddress, username)
            );

            return 0;
        }

        return Duration.between(
                now,
                resetAt
        ).toSeconds();
    }

    private String key(
            String ipAddress,
            String username) {

        return ipAddress + ":" + username;
    }

    private record Window(
            Instant startedAt,
            int count
    ) {
    }
}