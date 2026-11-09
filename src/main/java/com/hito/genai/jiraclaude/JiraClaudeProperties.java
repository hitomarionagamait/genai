package com.hito.genai.jiraclaude;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Konfigurasi integrasi Jira (prefix jira). Kredensial HANYA dari environment variable. */
@ConfigurationProperties(prefix = "jira")
public class JiraClaudeProperties {

    /** Contoh: https://nama-site.atlassian.net */
    private String baseUrl = "";
    private String email = "";
    private String apiToken = "";
    /** Project yang versinya muncul di dropdown fixVersion (pisahkan dengan koma). */
    private List<String> projectKeys = new ArrayList<>();
    /** Lokasi template Excel. */
    private String template = "classpath:jira/Pre_Deployment_Checklist_template.xlsx";
    private int cacheTtlMinutes = 10;
    private int maxIssues = 1000;
    private Jql jql = new Jql();
    private FieldNames fieldNames = new FieldNames();
    private DevOps devops = new DevOps();
    private Development development = new Development();
    private Bugs bugs = new Bugs();

    public static class Jql {
        /** Sumber sheet "All Ticket". {fixVersion} diganti dengan versi yang dipilih. */
        private String allTickets = "\"Platform[Dropdown]\" = Backend AND fixVersion = \"{fixVersion}\" "
                + "AND worktype != Bug AND status = Done ORDER BY created DESC";
        /** Sumber sheet "DevOps Ticket" (biasanya project lain). Kosong = tidak ditarik. */
        private String devops = "\"Application[Dropdown]\"=\"Cluster UBP\" and \"Apps Version[Dropdown]\"={fixVersionShort} order by created DESC";
        /** Sumber sheet "DDL Ticket". Kosong = diturunkan dari All Ticket (summary memuat "DDL"). */
        private String ddl = "";
        /** Sumber sheet "Hotfix OR Sanity Tickets". Kosong = diturunkan dari All Ticket (summary memuat hotfix/sanity). */
        private String hotfix = "";
        private String db = "";

        public String getAllTickets() { return allTickets; }
        public void setAllTickets(String v) { this.allTickets = v; }
        public String getDevops() { return devops; }
        public void setDevops(String v) { this.devops = v; }
        public String getDdl() { return ddl; }
        public String getDb() { return db; }
        public void setDdl(String v) { this.ddl = v; }
        public String getHotfix() { return hotfix; }
        public void setHotfix(String v) { this.hotfix = v; }
    }

    public static class FieldNames {
        /** Nama field di Jira; ID customfield_xxxxx ditemukan otomatis lewat GET /rest/api/3/field. */
        private List<String> storyPoints = List.of("Story Points", "Story point estimate");
        private List<String> sprint = List.of("Sprint");
        private List<String> usingPrdaApi = List.of("Using PRDA API?");
        /** Opsional: paksa ID field tertentu (mis. customfield_10016). */
        private String storyPointsId = "";
        private String sprintId = "";
        private String usingPrdaApiId = "";

        public List<String> getStoryPoints() { return storyPoints; }
        public void setStoryPoints(List<String> v) { this.storyPoints = v; }
        public List<String> getSprint() { return sprint; }
        public void setSprint(List<String> v) { this.sprint = v; }
        public List<String> getUsingPrdaApi() { return usingPrdaApi; }
        public void setUsingPrdaApi(List<String> v) { this.usingPrdaApi = v; }
        public String getStoryPointsId() { return storyPointsId; }
        public void setStoryPointsId(String v) { this.storyPointsId = v; }
        public String getSprintId() { return sprintId; }
        public void setSprintId(String v) { this.sprintId = v; }
        public String getUsingPrdaApiId() { return usingPrdaApiId; }
        public void setUsingPrdaApiId(String v) { this.usingPrdaApiId = v; }
    }

    public static class DevOps {
        /** Regex (group 1) untuk mengambil nama repository/service dari summary tiket DevOps. */
        private String repoPattern = "\\b(prdi-[a-z0-9]+(?:-[a-z0-9]+)*?-service|backend-api)\\b";

        public String getRepoPattern() { return repoPattern; }
        public void setRepoPattern(String v) { this.repoPattern = v; }
    }

    /** Data panel "Development" Jira (branch & pull request Bitbucket) lewat endpoint internal dev-status. */
    public static class Development {
        private boolean enabled = true;
        /** Jumlah panggilan paralel ke Jira. */
        private int parallelism = 6;
        /** Batas jumlah tiket yang diperiksa per report. */
        private int maxTickets = 300;
        /** Branch tujuan yang dianggap "main" pada kolom Pull Request To Main. */
        private List<String> mainBranches = List.of("main", "master");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getParallelism() { return parallelism; }
        public void setParallelism(int v) { this.parallelism = v; }
        public int getMaxTickets() { return maxTickets; }
        public void setMaxTickets(int v) { this.maxTickets = v; }
        public List<String> getMainBranches() { return mainBranches; }
        public void setMainBranches(List<String> v) { this.mainBranches = v; }
    }

    /** Dashboard monitoring bug (/bug-monitor). */
    public static class Bugs {
        /** JQL apa adanya (tanpa placeholder). Perhatikan: tidak memfilter tipe issue. */
        private String jql = "\"Platform[Dropdown]\"=Backend and project=\"PRDI Digital Ops\" order by created DESC";
        private int maxIssues = 2000;
        /** Nama prioritas yang dianggap kritis. */
        private List<String> criticalPriorities = List.of("Highest", "Critical", "Blocker", "High");
        /** Bug yang masih terbuka lebih lama dari ini (hari) ditandai "aging". */
        private int agingDays = 14;

        public String getJql() { return jql; }
        public void setJql(String v) { this.jql = v; }
        public int getMaxIssues() { return maxIssues; }
        public void setMaxIssues(int v) { this.maxIssues = v; }
        public List<String> getCriticalPriorities() { return criticalPriorities; }
        public void setCriticalPriorities(List<String> v) { this.criticalPriorities = v; }
        public int getAgingDays() { return agingDays; }
        public void setAgingDays(int v) { this.agingDays = v; }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String v) { this.apiToken = v; }
    public List<String> getProjectKeys() { return projectKeys; }
    public void setProjectKeys(List<String> v) { this.projectKeys = v; }
    public String getTemplate() { return template; }
    public void setTemplate(String v) { this.template = v; }
    public int getCacheTtlMinutes() { return cacheTtlMinutes; }
    public void setCacheTtlMinutes(int v) { this.cacheTtlMinutes = v; }
    public int getMaxIssues() { return maxIssues; }
    public void setMaxIssues(int v) { this.maxIssues = v; }
    public Jql getJql() { return jql; }
    public void setJql(Jql v) { this.jql = v; }
    public FieldNames getFieldNames() { return fieldNames; }
    public void setFieldNames(FieldNames v) { this.fieldNames = v; }
    public Bugs getBugs() { return bugs; }
    public void setBugs(Bugs v) { this.bugs = v; }
    public Development getDevelopment() { return development; }
    public void setDevelopment(Development v) { this.development = v; }
    public DevOps getDevops() { return devops; }
    public void setDevops(DevOps v) { this.devops = v; }
}
