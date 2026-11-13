package com.hito.genai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mengambil profil pengguna Bitbucket Cloud dengan access token OAuth: akun, email TERKONFIRMASI, dan workspace.
 * Bitbucket tidak mendukung OpenID Connect, jadi inilah satu-satunya bukti identitas yang kita punya.
 *
 * Catatan keamanan: tautan "next" pada respons berhalaman hanya diikuti bila tetap di host API Bitbucket,
 * agar access token tidak pernah terkirim ke host lain.
 */
@Component
public class BitbucketProfileClient {

    private static final Logger log = LoggerFactory.getLogger(BitbucketProfileClient.class);
    private static final int MAX_PAGES = 5;
    private static final int MAX_VALUES = 200;

    public record Profile(String accountId, String uuid, String displayName, String nickname, String primaryEmail,
                          List<String> confirmedEmails, List<String> workspaces) {
    }

    public static class BitbucketException extends RuntimeException {
        private final int status;

        public BitbucketException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final String base;
    private final RestClient rest;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public BitbucketProfileClient(SecurityProperties props) {
        String b = props.getBitbucketApiBase() == null ? "" : props.getBitbucketApiBase().trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.base = b;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.rest = RestClient.builder().requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
    }

    /** Akun wajib berhasil dibaca; email dan workspace bersifat "sebisanya" (gagal = daftar kosong + peringatan log). */
    public Profile fetch(String accessToken) {
        JsonNode user = get(base + "/2.0/user", accessToken);
        String accountId = user.path("account_id").asString("");
        if (accountId.isBlank()) {
            throw new BitbucketException("Respons /user tanpa account_id.", 0);
        }

        Set<String> confirmed = new LinkedHashSet<>();
        String primary = "";
        try {
            for (JsonNode e : pages(base + "/2.0/user/emails?pagelen=100", accessToken)) {
                String email = e.path("email").asString("");
                if (!email.isBlank() && e.path("is_confirmed").asBoolean(false)) {
                    confirmed.add(email);
                    if (e.path("is_primary").asBoolean(false) && primary.isEmpty()) {
                        primary = email;
                    }
                }
            }
        } catch (BitbucketException ex) {
            log.warn("Email Bitbucket tidak terbaca (status {}): pastikan consumer punya izin Email: Read.", ex.status());
        }
        if (primary.isEmpty() && !confirmed.isEmpty()) {
            primary = confirmed.iterator().next();
        }

        List<String> workspaces = workspaces(accessToken);
        return new Profile(accountId, user.path("uuid").asString(""), user.path("display_name").asString(""),
                user.path("nickname").asString(""), primary, List.copyOf(confirmed), workspaces);
    }

    /** Endpoint baru /user/workspaces dulu; /user/permissions/workspaces (deprecated) sebagai cadangan. */
    private List<String> workspaces(String token) {
        for (String path : List.of("/2.0/user/workspaces?pagelen=100", "/2.0/user/permissions/workspaces?pagelen=100")) {
            try {
                Set<String> slugs = new LinkedHashSet<>();
                for (JsonNode v : pages(base + path, token)) {
                    String slug = v.path("workspace").path("slug").asString(v.path("slug").asString(""));
                    if (!slug.isBlank()) {
                        slugs.add(slug);
                    }
                }
                return List.copyOf(slugs);
            } catch (BitbucketException ex) {
                log.warn("Daftar workspace Bitbucket dari {} gagal (status {}).", path.substring(0, path.indexOf('?')), ex.status());
            }
        }
        return List.of();
    }

    /** Mengumpulkan "values" dari respons berhalaman; hanya mengikuti "next" yang tetap di host API Bitbucket. */
    private List<JsonNode> pages(String firstUrl, String token) {
        List<JsonNode> out = new ArrayList<>();
        String url = firstUrl;
        for (int i = 0; i < MAX_PAGES && url != null && out.size() < MAX_VALUES; i++) {
            JsonNode page = get(url, token);
            for (JsonNode v : page.path("values")) {
                out.add(v);
            }
            String next = page.path("next").asString("");
            url = next.startsWith(base + "/") ? next : null;
        }
        return out;
    }

    private JsonNode get(String url, String token) {
        if (!url.startsWith(base + "/")) {
            throw new BitbucketException("URL di luar host API Bitbucket ditolak.", 0);
        }
        try {
            String body = rest.get().uri(URI.create(url)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new BitbucketException("Bitbucket mengembalikan " + res.getStatusCode().value(), res.getStatusCode().value());
                    })
                    .body(String.class);
            return mapper.readTree(body == null ? "{}" : body);
        } catch (BitbucketException e) {
            throw e;
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            throw new BitbucketException("Tidak bisa menghubungi Bitbucket: " + e.getClass().getSimpleName(), 0);
        }
    }
}
