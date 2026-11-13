package com.hito.genai.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** Halaman login: tombol Google/Bitbucket (bila aktif) dan form login lokal (bila aktif). */
@Controller
public class LoginController {

    /** Teks tetap per kode alasan; tidak pernah menampilkan teks bebas dari penyedia. */
    private static final Map<String, String> REASONS = Map.of(
            LoginPolicy.DENY_NOT_ALLOWED, "Akun ini tidak termasuk daftar akun yang diizinkan.",
            LoginPolicy.DENY_UNVERIFIED, "Email akun belum terverifikasi oleh penyedia login.",
            LoginPolicy.DENY_MISSING_EMAIL, "Penyedia login tidak memberikan alamat email yang valid.",
            LoginPolicy.DENY_ISSUER, "Token dari penyedia login tidak valid.",
            LoginPolicy.DENY_IDENTITY, "Identitas akun tidak dapat dipastikan oleh penyedia login.",
            LoginPolicy.DENY_NOT_CONFIGURED, "Daftar akun yang diizinkan belum dikonfigurasi oleh administrator.",
            LoginPolicy.DENY_PROVIDER, "Login dengan penyedia tersebut gagal. Silakan coba lagi.");

    private final SecurityProperties props;
    private final ObjectProvider<OAuthProviders> providers;

    public LoginController(SecurityProperties props, ObjectProvider<OAuthProviders> providers) {
        this.props = props;
        this.providers = providers;
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String reason,
                        @RequestParam(required = false) String logout, Model model) {
        OAuthProviders p = providers.getIfAvailable();
        model.addAttribute("providers", p == null ? List.of() : p.links());
        model.addAttribute("localLogin", props.isLocalLoginEnabled());
        String message = null;
        if (error != null) {
            message = "oauth".equals(error)
                    ? REASONS.getOrDefault(reason == null ? "" : reason, REASONS.get(LoginPolicy.DENY_PROVIDER))
                    : "Username atau password salah.";
        }
        model.addAttribute("errorMessage", message);
        model.addAttribute("loggedOut", logout != null);
        return "login";
    }
}
