package com.hito.genai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Menolak (429) percobaan login lokal dari username+IP yang sedang dikunci, SEBELUM password diperiksa.
 * Berlaku untuk POST /login (form) dan header Authorization: Basic. Sengaja bukan @Component supaya tidak
 * terdaftar dua kali di luar rantai Spring Security.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleFilter.class);
    private final LoginThrottle throttle;

    public LoginThrottleFilter(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String user = null;
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            user = basicUser(auth.substring(6).trim());
        } else if ("POST".equalsIgnoreCase(req.getMethod()) && "/login".equals(req.getServletPath())) {
            user = req.getParameter("username");
        }
        if (user != null) {
            long wait = throttle.lockedSeconds(user, req.getRemoteAddr());
            if (wait > 0) {
                log.warn("LOGIN_BLOCKED ip={} sisa={}s", req.getRemoteAddr(), wait);
                res.setStatus(429);
                res.setHeader("Retry-After", Long.toString(wait));
                res.setContentType("text/plain;charset=UTF-8");
                res.getWriter().write("Terlalu banyak percobaan login. Coba lagi dalam " + ((wait + 59) / 60) + " menit.");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static String basicUser(String base64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            int i = decoded.indexOf(':');
            return i < 0 ? decoded : decoded.substring(0, i);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
