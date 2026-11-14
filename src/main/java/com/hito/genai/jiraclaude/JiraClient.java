package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.VersionOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Klien Jira Cloud (REST v3). Autentikasi Basic (email + API token).
 * - Pencarian : POST /rest/api/3/search/jql dengan pagination nextPageToken
 * - Field     : GET  /rest/api/3/field (mencari ID customfield berdasarkan nama)
 * - Versi     : GET  /rest/api/3/project/{key}/versions
 */
@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    private final JiraClaudeProperties props;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile RestClient rest;
    private volatile Map<String, String> fieldIdsByName;

    public JiraClient(JiraClaudeProperties props) {
        this.props = props;
    }

    public boolean isConfigured() {
        return notBlank(props.getBaseUrl()) && notBlank(props.getEmail()) && notBlank(props.getApiToken());
    }

    public String baseUrl() {
        String b = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    public String browseUrl(String key) {
        return baseUrl() + "/browse/" + key;
    }

    // ------------------------------------------------------------------ API

    /** Ambil semua issue (maks maxIssues) untuk sebuah JQL. */
    public List<JsonNode> search(String jql, List<String> fields, int maxIssues) {
        List<JsonNode> out = new ArrayList<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES && out.size() < maxIssues; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jql", jql);
            body.put("fields", fields);
            body.put("maxResults", Math.min(PAGE_SIZE, maxIssues - out.size()));
            if (token != null) {
                body.put("nextPageToken", token);
            }
            JsonNode res = post("/rest/api/3/search/jql", body);
            JsonNode issues = res.path("issues");
            if (issues.isArray()) {
                for (JsonNode issue : issues) {
                    out.add(issue);
                }
            }
            token = res.hasNonNull("nextPageToken") ? res.get("nextPageToken").asString() : null;
            if (res.path("isLast").asBoolean(false) || token == null || token.isBlank()
                    || !issues.isArray() || issues.isEmpty()) {
                break;
            }
        }
        return out;
    }

    public List<VersionOption> versions(String projectKey) {
        JsonNode arr = get("/rest/api/3/project/{key}/versions", projectKey);
        List<VersionOption> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode v : arr) {
                String name = v.path("name").asString("");
                if (!name.isBlank()) {
                    out.add(new VersionOption(name, v.path("released").asBoolean(false),
                            v.path("archived").asBoolean(false), v.path("releaseDate").asString("")));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Development (endpoint internal Jira)

    private static final java.util.regex.Pattern NUMERIC = java.util.regex.Pattern.compile("^\\d{1,18}$");
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    /**
     * Ringkasan panel Development satu issue (jumlah branch/PR per jenis integrasi).
     * Endpoint INTERNAL Jira (tidak didokumentasikan resmi); bisa berubah tanpa pemberitahuan.
     */
    public JsonNode devSummary(String issueId) {
        if (issueId == null || !NUMERIC.matcher(issueId).matches()) {
            throw new JiraException("ID issue tidak valid untuk dev-status.");
        }
        return get("/rest/dev-status/latest/issue/summary?issueId={id}", issueId);
    }

    /** Detail panel Development; dataType: pullrequest | branch. */
    public JsonNode devDetail(String issueId, String applicationType, String dataType) {
        if (issueId == null || !NUMERIC.matcher(issueId).matches() || applicationType == null
                || !TOKEN.matcher(applicationType).matches() || !java.util.Set.of("pullrequest", "branch").contains(dataType)) {
            throw new JiraException("Parameter dev-status tidak valid.");
        }
        return get("/rest/dev-status/latest/issue/detail?issueId={id}&applicationType={app}&dataType={type}",
                issueId, applicationType, dataType);
    }

    /** ID customfield untuk pasangan (storyPoints, sprint, usingPrdaApi); null bila tidak ditemukan. */
    public JiraIssueMapper.FieldIds resolveFieldIds() {
        JiraClaudeProperties.FieldNames cfg = props.getFieldNames();
        Map<String, String> byName = fieldIdsByName();
        return new JiraIssueMapper.FieldIds(
                pick(cfg.getStoryPointsId(), cfg.getStoryPoints(), byName),
                pick(cfg.getSprintId(), cfg.getSprint(), byName),
                pick(cfg.getUsingPrdaApiId(), cfg.getUsingPrdaApi(), byName));
    }

    private static String pick(String explicitId, List<String> names, Map<String, String> byName) {
        if (notBlank(explicitId)) {
            return explicitId.trim();
        }
        for (String n : names) {
            String id = byName.get(n.trim().toLowerCase(Locale.ROOT));
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private Map<String, String> fieldIdsByName() {
        Map<String, String> cached = fieldIdsByName;
        if (cached != null) {
            return cached;
        }
        Map<String, String> map = new HashMap<>();
        try {
            JsonNode arr = get("/rest/api/3/field");
            if (arr.isArray()) {
                for (JsonNode f : arr) {
                    String name = f.path("name").asString("").trim().toLowerCase(Locale.ROOT);
                    String id = f.path("id").asString("");
                    if (!name.isEmpty() && !id.isEmpty()) {
                        map.putIfAbsent(name, id);
                    }
                }
            }
            fieldIdsByName = map;
        } catch (JiraException e) {
            log.warn("Gagal membaca daftar field Jira: {}", e.getMessage());
        }
        return map;
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode get(String uri, Object... vars) {
        try {
            String body = rest().get().uri(uri, vars).retrieve()
                    .onStatus(HttpStatusCode::isError, this::fail).body(String.class);
            return parse(body);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    private JsonNode post(String uri, Object payload) {
        try {
            String body = rest().post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(payload).retrieve()
                    .onStatus(HttpStatusCode::isError, this::fail).body(String.class);
            return parse(body);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /** Koneksi gagal / timeout / DNS: pesan aman (tanpa kredensial) dan tidak menjadi HTTP 500. */
    private JiraException unreachable(RestClientException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.warn("Koneksi ke Jira gagal: {}", root.toString());
        return new JiraException("Tidak bisa terhubung ke Jira (" + root.getClass().getSimpleName()
                + "). Periksa JIRA_BASE_URL dan koneksi jaringan.", e);
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "{}" : body);
        } catch (RuntimeException e) {
            throw new JiraException("Respons Jira tidak valid (bukan JSON).", e);
        }
    }

    private void fail(org.springframework.http.HttpRequest request, ClientHttpResponse response) throws IOException {
        int code = response.getStatusCode().value();
        String body = new String(response.getBody().readNBytes(4000), StandardCharsets.UTF_8);
        String detail = "";
        try {
            JsonNode n = mapper.readTree(body);
            if (n.path("errorMessages").isArray() && !n.path("errorMessages").isEmpty()) {
                List<String> msgs = new ArrayList<>();
                n.path("errorMessages").forEach(m -> msgs.add(m.asString("")));
                detail = String.join("; ", msgs);
            }
        } catch (RuntimeException ignored) {
            // body bukan JSON: abaikan
        }
        String msg = switch (code) {
            case 401 -> "Jira menolak kredensial (401). Periksa JIRA_EMAIL dan JIRA_API_TOKEN.";
            case 403 -> "Akses ke Jira ditolak (403). Akun belum punya izin untuk data ini.";
            case 404 -> "Resource Jira tidak ditemukan (404). Periksa JIRA_BASE_URL / project key.";
            case 400 -> "Jira menolak permintaan (400)" + (detail.isEmpty() ? "." : ": " + detail);
            default -> "Jira mengembalikan error " + code + (detail.isEmpty() ? "." : ": " + detail);
        };
        throw new JiraException(msg, code);
    }

    private RestClient rest() {
        RestClient r = rest;
        if (r == null) {
            synchronized (this) {
                if (rest == null) {
                    if (!isConfigured()) {
                        throw new JiraException("Jira belum dikonfigurasi (JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN).");
                    }
                    HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(hc);
                    factory.setReadTimeout(Duration.ofSeconds(60));
                    String basic = Base64.getEncoder().encodeToString(
                            (props.getEmail().trim() + ":" + props.getApiToken().trim()).getBytes(StandardCharsets.UTF_8));
                    rest = RestClient.builder()
                            .baseUrl(baseUrl())
                            .requestFactory(factory)
                            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                            .build();
                }
                r = rest;
            }
        }
        return r;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
