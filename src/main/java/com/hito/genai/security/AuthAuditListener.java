package com.hito.genai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** Jejak audit login/logout. Identitas selalu disamarkan; password tidak pernah dicatat. */
@Component
public class AuthAuditListener {

    private static final Logger log = LoggerFactory.getLogger("AUTH_AUDIT");

    /** Dipublikasikan hanya oleh login interaktif (form dan OAuth2), bukan oleh setiap request Basic. */
    @EventListener
    public void onLogin(InteractiveAuthenticationSuccessEvent e) {
        Authentication a = e.getAuthentication();
        log.info("LOGIN_OK user={} via={} ip={}", LoginPolicy.mask(a.getName()), a.getClass().getSimpleName(), ip(a));
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent e) {
        Authentication a = e.getAuthentication();
        log.warn("LOGIN_FAIL user={} reason={} ip={}", LoginPolicy.mask(a.getName()),
                e.getException().getClass().getSimpleName(), ip(a));
    }

    @EventListener
    public void onLogout(LogoutSuccessEvent e) {
        log.info("LOGOUT user={}", LoginPolicy.mask(e.getAuthentication().getName()));
    }

    private static String ip(Authentication a) {
        return a.getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : "-";
    }
}
