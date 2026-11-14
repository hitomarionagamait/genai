package com.hito.genai.jiraclaude;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration for Bitbucket branch/pipeline lookup and SonarQube quality metrics. */
@ConfigurationProperties(prefix = "repository.quality")
public class RepositoryQualityProperties {

    private boolean enabled = true;
    private Bitbucket bitbucket = new Bitbucket();
    private Sonar sonar = new Sonar();
    private Whitelist whitelist = new Whitelist();
    private Environments environments = new Environments();

    public static class Environments {
        private Sit sit = new Sit();
        private Uat uat = new Uat();
        private Prod prod = new Prod();
        public static class Sit {
            /** Default SIT branch; only branches in the allow-list below can be selected. */
            private String branch = "develop-support";
            private List<String> branches = List.of("develop", "develop-support");
            private boolean selectable = true;
            public String getBranch() { return branch; }
            public void setBranch(String value) { branch = value; }
            public String getDefaultBranch() { return branch; }
            public void setDefaultBranch(String value) { branch = value; }
            public List<String> getBranches() { return branches; }
            public void setBranches(List<String> value) {
                branches = value == null ? List.of("develop", "develop-support")
                        : value.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
            }
            public boolean isSelectable() { return selectable; }
            public void setSelectable(boolean value) { selectable = value; }
        }
        public static class Uat {
            private String branchPattern = "release/{fixVersion}";
            private boolean selectable = false;
            public String getBranchPattern() { return branchPattern; }
            public void setBranchPattern(String value) { branchPattern = value; }
            public boolean isSelectable() { return selectable; }
            public void setSelectable(boolean value) { selectable = value; }
        }
        public static class Prod {
            private String branch = "main";
            private boolean selectable = false;
            public String getBranch() { return branch; }
            public void setBranch(String value) { branch = value; }
            public boolean isSelectable() { return selectable; }
            public void setSelectable(boolean value) { selectable = value; }
        }
        public Sit getSit() { return sit; }
        public void setSit(Sit value) { sit = value; }
        public Uat getUat() { return uat; }
        public void setUat(Uat value) { uat = value; }
        public Prod getProd() { return prod; }
        public void setProd(Prod value) { prod = value; }
    }

    public static class Bitbucket {
        private String baseUrl = "https://api.bitbucket.org/2.0";
        private String workspace = "";
        private String token = "";
        private int maxBranchPages = 10;
        private int pipelineLookback = 20;
        private boolean pipelineEnabled = false;
        private String unitTestStepPattern = "(?i).*(unit.?test|unittest|test|testing).*";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String value) { workspace = value; }
        public String getToken() { return token; }
        public void setToken(String value) { token = value; }
        public int getMaxBranchPages() { return maxBranchPages; }
        public void setMaxBranchPages(int value) { maxBranchPages = value; }
        public int getPipelineLookback() { return pipelineLookback; }
        public void setPipelineLookback(int value) { pipelineLookback = value; }
        public boolean isPipelineEnabled() { return pipelineEnabled; }
        public void setPipelineEnabled(boolean value) { pipelineEnabled = value; }
        public String getUnitTestStepPattern() { return unitTestStepPattern; }
        public void setUnitTestStepPattern(String value) { unitTestStepPattern = value; }
    }

    public static class Sonar {
        private String baseUrl = "";
        private String token = "";
        /** Key = Bitbucket repository slug, value = SonarQube project key. */
        private Map<String, String> projectKeys = new LinkedHashMap<>();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public String getToken() { return token; }
        public void setToken(String value) { token = value; }
        public Map<String, String> getProjectKeys() { return projectKeys; }
        public void setProjectKeys(Map<String, String> value) { projectKeys = value == null ? new LinkedHashMap<>() : value; }
    }

    public static class Whitelist {
        private String file = "./data/repository-coverage-whitelist.txt";
        public String getFile() { return file; }
        public void setFile(String value) { file = value; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Bitbucket getBitbucket() { return bitbucket; }
    public void setBitbucket(Bitbucket value) { bitbucket = value; }
    public Sonar getSonar() { return sonar; }
    public void setSonar(Sonar value) { sonar = value; }
    public Whitelist getWhitelist() { return whitelist; }
    public void setWhitelist(Whitelist value) { whitelist = value; }
    public Environments getEnvironments() { return environments; }
    public void setEnvironments(Environments value) { environments = value; }
}
