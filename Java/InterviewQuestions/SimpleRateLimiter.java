/*
 * SIMPLE JAVA - Rate limiter
 *
 * Functional requirements (MVP): Allow up to N requests per user per fixed-duration window; reject
 * excess requests. Non-functional requirements: readable, in-memory, safe access within one JVM.
 * Core entities: Window stores start time/count. RateLimiter maps user IDs to independent windows.
 * Operations and flow: allow: select user -> reset expired window -> check count -> increment if
 * allowed. Design choices and concurrency: synchronized protects the check-and-increment; inject
 * monotonic elapsed milliseconds. Assumptions / omitted features: Window starts at the first
 * request and resets on the next request after expiry. Boundary bursts are possible. No token
 * bucket, Redis, key eviction or distributed coordination. Edge cases to discuss/test: Exact expiry
 * boundary, separate users, backward time, concurrent requests for one user. Run from repository
 * root (Java 17+ and Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleRateLimiter
 * Read SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.HashMap;
import java.util.Map;

public class SimpleRateLimiter {
    static class Window {
        long startedAt;
        long lastSeen;
        int accepted;

        Window(long now) {
            startedAt = now;
            lastSeen = now;
        }
    }

    static class RateLimiter {
        private final int limit;
        private final long windowMillis;
        private final Map<String, Window> users = new HashMap<>();

        RateLimiter(int limit, long windowMillis) {
            if (limit <= 0 || windowMillis <= 0) {
                throw new IllegalArgumentException("Positive limit and window required");
            }
            this.limit = limit;
            this.windowMillis = windowMillis;
        }

        // now is elapsed monotonic milliseconds, injected to avoid sleeps in the lesson.
        synchronized boolean allow(String user, long now) {
            if (user == null || user.isBlank() || now < 0) {
                throw new IllegalArgumentException("Invalid user/time");
            }
            Window window = users.computeIfAbsent(user, ignored -> new Window(now));
            if (now < window.lastSeen) {
                throw new IllegalArgumentException("Clock moved backwards");
            }
            window.lastSeen = now;
            if (now - window.startedAt >= windowMillis) {
                window.startedAt = now;
                window.accepted = 0;
            }
            // Check and increment together: concurrent calls cannot exceed this window's limit.
            if (window.accepted == limit) {
                return false;
            }
            window.accepted++;
            return true;
        }
    }

    public static void main(String[] args) {
        RateLimiter limiter = new RateLimiter(2, 1000);
        System.out.println("First: " + limiter.allow("alice", 0));
        System.out.println("Second: " + limiter.allow("alice", 100));
        System.out.println("Third: " + limiter.allow("alice", 200));
        System.out.println("Next window: " + limiter.allow("alice", 1000));
    }
}
