package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.RepositoryQualityModels.BranchRef;
import com.hito.genai.jiraclaude.RepositoryQualityModels.PipelineInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only Bitbucket Cloud client for branches and latest Unit Test pipeline metadata. */
@Component
public class BitbucketRepositoryQualityClient {
    private static final Logger log = LoggerFactory.getLogger(BitbucketRepositoryQualityClient.class);

    private final RepositoryQualityProperties properties;
    private final RestClient rest;
    private final Pattern unitTestPattern;

    public BitbucketRepositoryQualityClient(RestClient.Builder builder, RepositoryQualityProperties properties) {
        this.properties = properties;
        String base = trimTrailingSlash(properties.getBitbucket().getBaseUrl());
        RestClient.Builder clientBuilder = builder.clone().baseUrl(base);
        String token = properties.getBitbucket().getToken();
        if (token != null && !token.isBlank()) {
            clientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        this.rest = clientBuilder.build();
        String regex = properties.getBitbucket().getUnitTestStepPattern();
        this.unitTestPattern = Pattern.compile(regex == null || regex.isBlank()
                ? "(?i).*(unit.?test|test|verify|jacoco|coverage).*" : regex);
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && hasText(properties.getBitbucket().getWorkspace())
                && hasText(properties.getBitbucket().getToken());
    }

    public List<BranchRef> listBranches(String repoSlug) {
        ensureConfigured();
        List<BranchRef> result = new ArrayList<>();
        String next = null;
        int page = 0;
        do {
            JsonNode response = next == null
                    ? rest.get().uri(uriBuilder -> uriBuilder
                            .path("/repositories/{workspace}/{repo}/refs/branches")
                            .queryParam("pagelen", 100)
                            .build(properties.getBitbucket().getWorkspace(), repoSlug))
                    .retrieve().body(JsonNode.class)
                    : rest.get().uri(URI.create(next)).retrieve().body(JsonNode.class);
            if (response == null) break;
            JsonNode values = response.path("values");
            if (values.isArray()) {
                for (JsonNode branch : values) {
                    String name = branch.path("name").asString("");
                    if (!name.isBlank()) {
                        result.add(new BranchRef(name,
                                branch.path("target").path("hash").asString(""),
                                branch.path("links").path("html").path("href").asString("")));
                    }
                }
            }
            next = response.path("next").asString("");
            if (next.isBlank()) next = null;
            page++;
        } while (next != null && page < Math.max(1, properties.getBitbucket().getMaxBranchPages()));
        if (next != null) {
            throw new IllegalStateException("Daftar branch repository " + repoSlug
                    + " melebihi batas halaman " + properties.getBitbucket().getMaxBranchPages()
                    + ". Naikkan repository-quality.bitbucket.max-branch-pages.");
        }
        return List.copyOf(result);
    }

    /** Find the newest pipeline run on a branch that contains a step matching the test pattern. */
    public PipelineInfo latestUnitTestPipeline(String repoSlug, String branch) {
        return latestUnitTestPipeline(properties.getBitbucket().getWorkspace(), repoSlug, branch);
    }

    /** Same lookup, but allows a dedicated workspace for a specific repository integration. */
    public PipelineInfo latestUnitTestPipeline(String workspace, String repoSlug, String branch) {
        ensureConfigured(workspace);
        JsonNode page = rest.get().uri(uriBuilder -> uriBuilder
                        .path("/repositories/{workspace}/{repo}/pipelines")
                        .queryParam("q", "target.ref_name=\"" + escapeQueryValue(branch) + "\"")
                        .queryParam("sort", "-created_on")
                        .queryParam("pagelen", Math.max(1, Math.min(50, properties.getBitbucket().getPipelineLookback())))
                        .build(workspace, repoSlug))
                .retrieve().body(JsonNode.class);
        if (page == null || !page.path("values").isArray()) {
            return PipelineInfo.notFound("Tidak ada data pipeline untuk branch ini.");
        }

        for (JsonNode pipeline : page.path("values")) {
            String uuid = pipeline.path("uuid").asString("");
            JsonNode steps = uuid.isBlank() ? null : getSteps(workspace, repoSlug, uuid);
            TestStepMatch matchingStep = findUnitTestStep(workspace, repoSlug, uuid, steps);
            if (matchingStep != null) {
                // Prefer the matched Testing/Unit Test step result; fall back to pipeline result
                // when the step response omits state.result.
                String status = firstNonBlank(matchingStep.status(), stateName(pipeline.path("state")), "UNKNOWN");
                String build = pipeline.hasNonNull("build_number") ? pipeline.path("build_number").toString() : "";
                if (build.startsWith("\"") && build.endsWith("\"")) build = build.substring(1, build.length() - 1);
                String url = pipeline.path("links").path("html").path("href").asString("");
                return new PipelineInfo(build, status, matchingStep.label(),
                        pipeline.path("target").path("commit").path("hash").asString(""),
                        pipeline.path("completed_on").asString(""), url, "", branch);
            }
        }
        return PipelineInfo.notFound("Pipeline dengan step Testing/Unit Test tidak ditemukan pada "
                + properties.getBitbucket().getPipelineLookback() + " pipeline terakhir.");
    }

    /**
     * Return the newest pipeline run for SIT/UAT/PROD in one bounded repository-level lookup.
     * A run qualifies either by target branch (SIT: selected/develop/develop-support, UAT:
     * release/{fixVersion}, PROD: main) or by its deployment environment label (SIT*, UAT*, PROD*).
     * This reads recent pipeline runs only; it does not enumerate repository branches.
     */
    public Map<String, PipelineInfo> latestDeploymentEnvironmentPipelines(String workspace, String repoSlug,
                                                                          String sitBranch, String uatBranch,
                                                                          String prodBranch) {
        ensureConfigured(workspace);
        Map<String, PipelineInfo> found = new LinkedHashMap<>();

        // First query the target branches independently. This avoids a noisy SIT branch
        // consuming the whole repository-wide lookback window and hiding the latest UAT/PROD run.
        LinkedHashSet<String> sitCandidates = new LinkedHashSet<>();
        if (sitBranch != null && !sitBranch.isBlank()) sitCandidates.add(sitBranch.trim());
        sitCandidates.add("develop-support");
        sitCandidates.add("develop");
        for (String candidate : sitCandidates) {
            PipelineInfo pipeline = latestPipelineForBranch(workspace, repoSlug, candidate);
            if (pipeline != null) {
                found.put("SIT", pipeline);
                break;
            }
        }
        PipelineInfo uat = latestPipelineForBranch(workspace, repoSlug, uatBranch);
        if (uat != null) found.put("UAT", uat);
        PipelineInfo prod = latestPipelineForBranch(workspace, repoSlug,
                prodBranch == null || prodBranch.isBlank() ? "main" : prodBranch);
        if (prod != null) found.put("PROD", prod);
        if (found.size() == 3) return found;

        // Fallback for pipelines whose target branch name itself carries SIT*/UAT*/PROD*
        // or whose deployment environment is labelled SIT*/UAT*/PROD*.
        int lookback = Math.max(1, Math.min(100, properties.getBitbucket().getPipelineLookback()));
        JsonNode page = rest.get().uri(uriBuilder -> uriBuilder
                        .path("/repositories/{workspace}/{repo}/pipelines")
                        .queryParam("sort", "-created_on")
                        .queryParam("pagelen", lookback)
                        .build(workspace, repoSlug))
                .retrieve().body(JsonNode.class);
        if (page == null || !page.path("values").isArray()) return found;
        for (JsonNode pipeline : page.path("values")) {
            if (found.size() == 3) break;
            String branch = pipeline.path("target").path("ref_name").asString("");
            Set<String> branchEnvs = environmentsForBranch(branch, sitBranch, uatBranch, prodBranch);
            String uuid = pipeline.path("uuid").asString("");
            Map<String, TestStepMatch> environmentMatches = environmentStepMatches(
                    workspace, repoSlug, uuid, null, pipeline.path("target"));

            boolean missingEnvironmentLabel = List.of("SIT", "UAT", "PROD").stream()
                    .anyMatch(env -> !found.containsKey(env) && !branchEnvs.contains(env));
            if (missingEnvironmentLabel && !uuid.isBlank()) {
                JsonNode steps = getSteps(workspace, repoSlug, uuid);
                environmentMatches = environmentStepMatches(
                        workspace, repoSlug, uuid, steps, pipeline.path("target"));
            }

            String pipelineStatus = stateName(pipeline.path("state"));
            for (String env : List.of("SIT", "UAT", "PROD")) {
                if (found.containsKey(env)) continue;
                TestStepMatch environmentStep = environmentMatches.get(env);
                if (!branchEnvs.contains(env) && environmentStep == null) continue;
                String status = environmentStep != null && !environmentStep.status().isBlank()
                        ? environmentStep.status() : pipelineStatus;
                String stepName = environmentStep == null ? "" : environmentStep.label();
                found.put(env, pipelineInfo(pipeline, branch, stepName, status,
                        "Pipeline terpilih berdasarkan branch atau deployment environment " + env + "."));
            }
        }
        return found;
    }

    private PipelineInfo latestPipelineForBranch(String workspace, String repoSlug, String branch) {
        if (branch == null || branch.isBlank()) return null;
        JsonNode page = rest.get().uri(uriBuilder -> uriBuilder
                        .path("/repositories/{workspace}/{repo}/pipelines")
                        .queryParam("q", "target.ref_name=\"" + escapeQueryValue(branch) + "\"")
                        .queryParam("sort", "-created_on")
                        .queryParam("pagelen", 1)
                        .build(workspace, repoSlug))
                .retrieve().body(JsonNode.class);
        if (page == null || !page.path("values").isArray() || page.path("values").isEmpty()) return null;
        JsonNode pipeline = page.path("values").get(0);
        String actualBranch = pipeline.path("target").path("ref_name").asString("");
        if (actualBranch.isBlank()) actualBranch = branch;
        return pipelineInfo(pipeline, actualBranch, "", stateName(pipeline.path("state")),
                "Pipeline terbaru untuk target branch " + branch + ".");
    }

    private static PipelineInfo pipelineInfo(JsonNode pipeline, String fallbackBranch, String stepName,
                                             String status, String note) {
        String branch = pipeline.path("target").path("ref_name").asString("");
        if (branch.isBlank()) branch = fallbackBranch == null ? "" : fallbackBranch;
        return new PipelineInfo(scalarText(pipeline.path("build_number")),
                status == null ? "" : status, stepName == null ? "" : stepName,
                pipeline.path("target").path("commit").path("hash").asString(""),
                pipeline.path("completed_on").asString(""),
                pipeline.path("links").path("html").path("href").asString(""),
                note == null ? "" : note, branch);
    }

    private Set<String> environmentsForBranch(String branch, String sitBranch, String uatBranch, String prodBranch) {
        Set<String> result = new LinkedHashSet<>();
        String value = normaliseBranch(branch);
        if (sameBranch(value, sitBranch) || sameBranch(value, "develop") || sameBranch(value, "develop-support")
                || startsWithEnvironment(value, "SIT")) result.add("SIT");
        if (sameBranch(value, uatBranch) || startsWithEnvironment(value, "UAT")) result.add("UAT");
        if (sameBranch(value, prodBranch) || sameBranch(value, "main") || startsWithEnvironment(value, "PROD")) result.add("PROD");
        return result;
    }

    private Map<String, TestStepMatch> environmentStepMatches(String workspace, String repoSlug, String pipelineUuid,
                                                              JsonNode steps, JsonNode target) {
        Map<String, TestStepMatch> matches = new LinkedHashMap<>();
        addEnvironmentLabel(matches, firstNonBlank(
                target.path("deployment_environment").path("name").asString(""),
                textValue(target.path("deployment_environment")),
                target.path("environment").path("name").asString(""),
                textValue(target.path("environment"))), "", "");
        if (steps == null || !steps.path("values").isArray()) return matches;
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode step : steps.path("values")) values.add(step);
        collectEnvironmentSteps(matches, values);
        if (matches.size() < 3 && !pipelineUuid.isBlank()) {
            // Detail endpoint may contain deployment_environment omitted by the list endpoint.
            List<JsonNode> detailed = new ArrayList<>();
            for (JsonNode step : values) {
                String stepUuid = step.path("uuid").asString("");
                JsonNode detail = stepUuid.isBlank() ? null : getStepDetails(workspace, repoSlug, pipelineUuid, stepUuid);
                detailed.add(detail == null ? step : detail);
            }
            collectEnvironmentSteps(matches, detailed);
        }
        return matches;
    }

    private void collectEnvironmentSteps(Map<String, TestStepMatch> matches, List<JsonNode> steps) {
        for (JsonNode step : steps) {
            JsonNode stage = step.path("stage");
            String stepName = firstNonBlank(step.path("name").asString(""), step.path("step_name").asString(""),
                    step.path("label").asString(""));
            String envName = firstNonBlank(
                    step.path("deployment_environment").path("name").asString(""),
                    textValue(step.path("deployment_environment")),
                    step.path("environment").path("name").asString(""),
                    textValue(step.path("environment")),
                    textValue(step.path("deployment")),
                    stage.path("deployment_environment").path("name").asString(""),
                    textValue(stage.path("deployment_environment")),
                    stage.path("environment").path("name").asString(""),
                    textValue(stage.path("environment")),
                    textValue(stage.path("deployment")),
                    stage.path("name").asString(""));
            String status = firstNonBlank(stateName(step.path("state")), stateName(stage.path("state")));
            addEnvironmentLabel(matches, envName, stepName, status);
        }
    }

    private static void addEnvironmentLabel(Map<String, TestStepMatch> matches, String environmentName,
                                            String stepName, String status) {
        for (String env : List.of("SIT", "UAT", "PROD")) {
            if (startsWithEnvironment(environmentName, env) || startsWithEnvironment(stepName, env)) {
                matches.putIfAbsent(env, new TestStepMatch(firstNonBlank(environmentName, stepName), status));
            }
        }
    }

    private static boolean sameBranch(String a, String b) {
        return b != null && !b.isBlank() && normaliseBranch(a).equals(normaliseBranch(b));
    }

    private static String normaliseBranch(String value) {
        if (value == null) return "";
        String branch = value.trim().toLowerCase(Locale.ROOT);
        if (branch.startsWith("refs/heads/")) branch = branch.substring("refs/heads/".length());
        if (branch.startsWith("refs/tags/")) branch = branch.substring("refs/tags/".length());
        return branch;
    }

    /** SIT*, UAT* and PROD* are prefix patterns, including deployment-environment names. */
    private static boolean startsWithEnvironment(String value, String env) {
        return value != null && value.trim().toUpperCase(Locale.ROOT).startsWith(env);
    }

    private JsonNode getSteps(String workspace, String repoSlug, String pipelineUuid) {
        try {
            return rest.get().uri(uriBuilder -> uriBuilder
                            .path("/repositories/{workspace}/{repo}/pipelines/{pipeline}/steps")
                            .queryParam("pagelen", 100)
                            .queryParam("fields", "*")
                            .build(workspace, repoSlug, pipelineUuid))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            log.warn("Tidak dapat membaca step pipeline repo {}/{}: {}", workspace, repoSlug, ex.getMessage());
            return null;
        }
    }

    private record TestStepMatch(String label, String status) {}

    /**
     * The list-steps response may omit step/environment labels. In that case, fetch each step's
     * detail endpoint so a Testing deployment environment or a named Testing step can still be
     * identified reliably.
     */
    private TestStepMatch findUnitTestStep(String workspace, String repoSlug, String pipelineUuid, JsonNode steps) {
        if (steps == null || !steps.path("values").isArray()) return null;
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode step : steps.path("values")) values.add(step);

        // First use fields already present in the list response (avoids extra API calls).
        TestStepMatch match = matchTestStep(values);
        if (match != null) return match;

        // Then enrich missing metadata from GET /steps/{step_uuid}.
        List<JsonNode> detailed = new ArrayList<>();
        for (JsonNode step : values) {
            String stepUuid = step.path("uuid").asString("");
            JsonNode detail = stepUuid.isBlank() ? null : getStepDetails(workspace, repoSlug, pipelineUuid, stepUuid);
            detailed.add(detail == null ? step : detail);
        }
        return matchTestStep(detailed);
    }

    private TestStepMatch matchTestStep(List<JsonNode> steps) {
        TestStepMatch lastEnvironmentMatch = null;
        TestStepMatch lastStepMatch = null;
        for (JsonNode step : steps) {
            String stepName = firstNonBlank(
                    step.path("name").asString(""),
                    step.path("step_name").asString(""),
                    step.path("label").asString(""));
            JsonNode stage = step.path("stage");
            String environmentName = firstNonBlank(
                    step.path("deployment_environment").path("name").asString(""),
                    textValue(step.path("deployment_environment")),
                    step.path("environment").path("name").asString(""),
                    textValue(step.path("environment")),
                    textValue(step.path("deployment")),
                    stage.path("deployment_environment").path("name").asString(""),
                    textValue(stage.path("deployment_environment")),
                    stage.path("environment").path("name").asString(""),
                    textValue(stage.path("environment")),
                    textValue(stage.path("deployment")),
                    stage.path("name").asString(""));
            String status = stateName(step.path("state"));
            if (!environmentName.isBlank() && unitTestPattern.matcher(environmentName).matches()) {
                lastEnvironmentMatch = new TestStepMatch(environmentName
                        + (stepName.isBlank() ? "" : " · " + stepName), status);
            } else if (!stepName.isBlank() && unitTestPattern.matcher(stepName).matches()) {
                lastStepMatch = new TestStepMatch(stepName, status);
            }
        }
        // Environment match has priority over a similarly named individual step. The last
        // matching item is used because pipeline step lists are returned in execution order.
        return lastEnvironmentMatch != null ? lastEnvironmentMatch : lastStepMatch;
    }

    private JsonNode getStepDetails(String workspace, String repoSlug, String pipelineUuid, String stepUuid) {
        try {
            return rest.get().uri(uriBuilder -> uriBuilder
                            .path("/repositories/{workspace}/{repo}/pipelines/{pipeline}/steps/{step}")
                            .queryParam("fields", "*")
                            .build(workspace, repoSlug, pipelineUuid, stepUuid))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            log.debug("Tidak dapat membaca detail step pipeline {}/{} {}: {}",
                    workspace, repoSlug, stepUuid, ex.getMessage());
            return null;
        }
    }

    private static String scalarText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return value.asString("");
        if (value.isNumber() || value.isBoolean()) return value.toString();
        return "";
    }

    private static String textValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return value.asString("");
        if (value.isObject()) {
            return firstNonBlank(value.path("name").asString(""),
                    value.path("display_name").asString(""), value.path("value").asString(""));
        }
        return "";
    }

    private static String stateName(JsonNode state) {
        String result = firstNonBlank(state.path("result").path("name").asString(""),
                state.path("result").asString(""), state.path("status").asString(""));
        if (!result.isBlank()) return result;
        String name = state.path("name").asString("");
        // COMPLETED only describes the lifecycle; use the pipeline-level result in that case.
        return "COMPLETED".equalsIgnoreCase(name) ? "" : name;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    public boolean isConfiguredForWorkspace(String workspace) {
        return properties.isEnabled() && hasText(workspace) && hasText(properties.getBitbucket().getToken());
    }

    private void ensureConfigured() {
        ensureConfigured(properties.getBitbucket().getWorkspace());
    }

    private void ensureConfigured(String workspace) {
        if (!isConfiguredForWorkspace(workspace)) {
            throw new IllegalStateException("Bitbucket repository quality belum dikonfigurasi. Isi BITBUCKET_API_TOKEN dan workspace Bitbucket.");
        }
    }

    private static String escapeQueryValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String trimTrailingSlash(String value) {
        String base = value == null || value.isBlank() ? "https://api.bitbucket.org/2.0" : value.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }
}
