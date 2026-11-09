package com.hito.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BitbucketService {

    private final RestClient restClient;

    public BitbucketService(
            RestClient.Builder restClientBuilder,
            @Value("${bitbucket.api.token}") String token) {

        // Menggunakan Bearer Token (HTTP Access Token / Workspace Token)
        this.restClient = restClientBuilder
                .baseUrl("https://api.bitbucket.org/2.0")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    public String getPullRequestDiff(String prUrl) {
        // Regex untuk mengekstrak Workspace, Repo Slug, dan PR ID dari URL Bitbucket
        Pattern pattern = Pattern.compile("bitbucket\\.org/([^/]+)/([^/]+)/pull-requests/(\\d+)");
        Matcher matcher = pattern.matcher(prUrl);

        if (matcher.find()) {
            String workspace = matcher.group(1);
            String repoSlug = matcher.group(2);
            String prId = matcher.group(3);

            try {
                return restClient.get()
                        .uri("/repositories/{workspace}/{repo_slug}/pullrequests/{id}/diff", workspace, repoSlug, prId)
                        .retrieve()
                        .body(String.class);
            } catch (Exception e) {
                return "ERROR: Gagal mengambil diff dari Bitbucket. Pastikan Token valid dan memiliki akses 'Pull request: Read'. Detail: " + e.getMessage();
            }
        }

        return "ERROR: Format URL Bitbucket Pull Request tidak valid. Harap gunakan format: https://bitbucket.org/{workspace}/{repo}/pull-requests/{id}";
    }
}
