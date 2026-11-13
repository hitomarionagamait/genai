package com.hito.genai.security;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Aturan "siapa yang boleh masuk" untuk login Google dan Bitbucket. Kelas ini murni (tidak bergantung pada
 * library OAuth) dan dipanggil SETELAH identitas dibuktikan:
 * - Google    : Spring sudah memverifikasi tanda tangan, audience, kedaluwarsa, dan nonce ID token (OIDC).
 * - Bitbucket : tidak ada OIDC; identitas dibuktikan dengan access token yang dipakai memanggil API Bitbucket.
 *
 * Prinsip: tolak secara default (fail closed). Tanpa allowlist, tidak ada yang boleh masuk.
 */
@Component
public class LoginPolicy {

    /** Kode alasan penolakan; dipakai halaman login (tidak memuat teks bebas dari penyedia). */
    public static final String DENY_PROVIDER = "provider";
    public static final String DENY_ISSUER = "issuer";
    public static final String DENY_IDENTITY = "identity";
    public static final String DENY_MISSING_EMAIL = "missing_email";
    public static final String DENY_UNVERIFIED = "unverified";
    public static final String DENY_NOT_CONFIGURED = "not_configured";
    public static final String DENY_NOT_ALLOWED = "not_allowed";

    private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record Decision(boolean allowed, String code, Set<String> roles) {
        static Decision deny(String code) {
            return new Decision(false, code, Set.of());
        }
    }

    private final SecurityProperties props;

    public LoginPolicy(SecurityProperties props) {
        this.props = props;
    }

    /**
     * @param provider registration id ("google" / "bitbucket")
     * @param claims   google: iss, email, email_verified, hd.
     *                 bitbucket: account_id, confirmed_emails (Collection), workspaces (Collection slug).
     */
    public Decision evaluate(String provider, Map<String, Object> claims) {
        String p = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        return switch (p) {
            case "google" -> google(claims);
            case "bitbucket" -> bitbucket(claims);
            default -> Decision.deny(DENY_PROVIDER);
        };
    }

    // ------------------------------------------------------------------ Google (OIDC)

    private Decision google(Map<String, Object> claims) {
        if (!GOOGLE_ISSUERS.contains(str(claims.get("iss")))) {
            return Decision.deny(DENY_ISSUER);
        }
        String email = normalizeEmail(str(claims.get("email")));
        if (email == null) {
            return Decision.deny(DENY_MISSING_EMAIL);
        }
        if (props.isRequireEmailVerified() && !isTrue(claims.get("email_verified"))) {
            return Decision.deny(DENY_UNVERIFIED);
        }
        List<String> emails = lower(props.getAllowedEmails());
        List<String> domains = lower(props.getAllowedDomains());
        if (emails.isEmpty() && domains.isEmpty()) {
            return Decision.deny(DENY_NOT_CONFIGURED);
        }
        boolean allowed = emails.contains(email);
        if (!allowed) {
            String domain = domainOf(email);
            // Domain email saja tidak cukup: klaim "hd" (hosted domain Google Workspace) harus sama.
            allowed = domains.contains(domain) && domain.equals(str(claims.get("hd")).toLowerCase(Locale.ROOT));
        }
        return allowed ? allow(Set.of(email)) : Decision.deny(DENY_NOT_ALLOWED);
    }

    // ------------------------------------------------------------------ Bitbucket (OAuth2 biasa)

    private Decision bitbucket(Map<String, Object> claims) {
        if (str(claims.get("account_id")).isBlank()) {
            return Decision.deny(DENY_IDENTITY);
        }
        Set<String> confirmed = strings(claims.get("confirmed_emails")).stream()
                .map(LoginPolicy::normalizeEmail).filter(e -> e != null).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> workspaces = strings(claims.get("workspaces")).stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        List<String> emails = lower(props.getAllowedEmails());
        List<String> domains = lower(props.getAllowedDomains());
        List<String> allowedWs = lower(props.getAllowedBitbucketWorkspaces());
        if (emails.isEmpty() && domains.isEmpty() && allowedWs.isEmpty()) {
            return Decision.deny(DENY_NOT_CONFIGURED);
        }

        boolean byWorkspace = workspaces.stream().anyMatch(allowedWs::contains);
        boolean byEmail = confirmed.stream().anyMatch(e -> emails.contains(e) || domains.contains(domainOf(e)));
        if (!byWorkspace && !byEmail) {
            return Decision.deny(confirmed.isEmpty() && workspaces.isEmpty() ? DENY_MISSING_EMAIL : DENY_NOT_ALLOWED);
        }
        return allow(confirmed);
    }

    private Decision allow(Set<String> verifiedEmails) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add("ROLE_USER");
        List<String> admins = lower(props.getAdminEmails());
        if (verifiedEmails.stream().anyMatch(admins::contains)) {
            roles.add("ROLE_ADMIN");
        }
        return new Decision(true, "", Set.copyOf(roles));
    }

    // ------------------------------------------------------------------ util

    /** Huruf kecil, tanpa spasi tepi, tepat satu '@'; null bila bentuknya tidak valid. */
    static String normalizeEmail(String raw) {
        String e = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(e).matches() ? e : null;
    }

    private static String domainOf(String email) {
        return email.substring(email.lastIndexOf('@') + 1);
    }

    /** Untuk log: andi@example.com -> a***@example.com. */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email.charAt(0) + "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static boolean isTrue(Object v) {
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString());
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static List<String> strings(Object v) {
        return v instanceof Collection<?> c ? c.stream().filter(x -> x != null).map(Object::toString).toList() : List.of();
    }

    private static List<String> lower(List<String> in) {
        return in == null ? List.of() : in.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
    }
}
