package com.hito.genai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pembatas percobaan login lokal (form + Basic): mengunci pasangan username+IP setelah N kali gagal,
 * dan IP yang menebak banyak username (batas 4x lebih longgar). Penyimpanan di memori (cukup untuk 1 instance).
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
    private static final int IP_FACTOR = 4;
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Entry(int count, Instant windowStart, Instant lockedUntil) {
    }

    private final SecurityProperties props;
    private final Map<String, Entry> byUserIp = new ConcurrentHashMap<>();
    private final Map<String, Entry> byIp = new ConcurrentHashMap<>();

    public LoginThrottle(SecurityProperties props) {
        this.props = props;
    }

    /** Sisa detik penguncian; 0 bila tidak terkunci. */
    public long lockedSeconds(String username, String ip) {
        Instant now = Instant.now();
        long a = remaining(byUserIp.get(key(username, ip)), now);
        long b = remaining(byIp.get(ip), now);
        return Math.max(a, b);
    }

    public void failure(String username, String ip) {
        Instant now = Instant.now();
        hit(byUserIp, key(username, ip), Math.max(1, props.getMaxLoginAttempts()), now);
        hit(byIp, ip, Math.max(1, props.getMaxLoginAttempts()) * IP_FACTOR, now);
        if (byUserIp.size() > CLEANUP_THRESHOLD) {
            byUserIp.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
        if (byIp.size() > CLEANUP_THRESHOLD) {
            byIp.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
    }

    public void success(String username, String ip) {
        byUserIp.remove(key(username, ip));
    }

    private void hit(Map<String, Entry> map, String k, int limit, Instant now) {
        Duration window = Duration.ofMinutes(Math.max(1, props.getLockoutMinutes()));
        map.compute(k, (key, cur) -> {
            if (cur == null || now.isAfter(cur.windowStart().plus(window))
                    && (cur.lockedUntil() == null || now.isAfter(cur.lockedUntil()))) {
                cur = new Entry(0, now, null);
            }
            int count = cur.count() + 1;
            Instant lock = cur.lockedUntil();
            if (count >= limit && (lock == null || now.isAfter(lock))) {
                lock = now.plus(window);
                log.warn("LOGIN_LOCKED key={} selama {} menit", key.replaceAll("^[^|]*", "***"), window.toMinutes());
            }
            return new Entry(count, cur.windowStart(), lock);
        });
    }

    private static long remaining(Entry e, Instant now) {
        if (e == null || e.lockedUntil() == null || !now.isBefore(e.lockedUntil())) {
            return 0;
        }
        return Math.max(1, Duration.between(now, e.lockedUntil()).getSeconds());
    }

    private boolean expired(Entry e, Instant now) {
        Instant end = e.lockedUntil() != null ? e.lockedUntil()
                : e.windowStart().plus(Duration.ofMinutes(Math.max(1, props.getLockoutMinutes())));
        return now.isAfter(end);
    }

    static String key(String username, String ip) {
        return (username == null ? "" : username.trim().toLowerCase(Locale.ROOT)) + "|" + ip;
    }

    // ---- hitung otomatis dari event Spring Security (hanya login username/password) ----

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent e) {
        if (e.getAuthentication() instanceof UsernamePasswordAuthenticationToken t) {
            failure(t.getName(), ipOf(t));
        }
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent e) {
        if (e.getAuthentication() instanceof UsernamePasswordAuthenticationToken t) {
            success(t.getName(), ipOf(t));
        }
    }

    static String ipOf(UsernamePasswordAuthenticationToken t) {
        return t.getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : "unknown";
    }
}
