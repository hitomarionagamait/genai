package com.hito.genai.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Menyediakan "currentUser" ke semua halaman Thymeleaf (nama, penyedia login, admin atau bukan). */
@ControllerAdvice
public class CurrentUserAdvice {

    public record CurrentUser(String name, String provider, boolean admin) {
    }

    @ModelAttribute("currentUser")
    public CurrentUser currentUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String provider = "lokal";
        boolean admin = false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String name = a.getAuthority();
            if (name.startsWith("SOCIAL_")) {
                provider = name.substring("SOCIAL_".length());
            } else if (name.equals("ROLE_ADMIN")) {
                admin = true;
            }
        }
        return new CurrentUser(auth.getName(), provider, admin);
    }
}
