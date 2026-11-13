package com.hito.genai.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Konfigurasi keamanan aplikasi (prefix app.security). */
@ConfigurationProperties(prefix = "spring.security")
public class SecurityProperties {

    /** Login lokal (form + HTTP Basic). Matikan di produksi bila memakai Google/Bitbucket. */
    private boolean localLoginEnabled = true;
    /** Google: wajib klaim email_verified=true. (Bitbucket selalu hanya mempercayai email terkonfirmasi.) */
    private boolean requireEmailVerified = true;
    /** Email yang diizinkan login lewat Google/Bitbucket (persis, tanpa membedakan huruf besar/kecil). */
    private List<String> allowedEmails = new ArrayList<>();
    /** Domain email yang diizinkan (persis, bukan subdomain). Untuk Google wajib cocok dengan klaim "hd". */
    private List<String> allowedDomains = new ArrayList<>();
    /** Slug workspace Bitbucket yang anggotanya boleh masuk (lewat login Bitbucket). */
    private List<String> allowedBitbucketWorkspaces = new ArrayList<>();
    /** Basis URL API Bitbucket (diubah hanya untuk pengujian). */
    private String bitbucketApiBase = "https://api.bitbucket.org";
    /** Email yang mendapat ROLE_ADMIN (harus juga lolos allowlist di atas). */
    private List<String> adminEmails = new ArrayList<>();
    /** Penyedia yang memakai PKCE pada authorization request. */
    private List<String> pkceProviders = List.of("google");
    /** enforce | report-only | off */
    private String cspMode = "report-only";
    /** Jumlah gagal login lokal sebelum dikunci. */
    private int maxLoginAttempts = 5;
    private int lockoutMinutes = 15;

    public boolean isLocalLoginEnabled() { return localLoginEnabled; }
    public void setLocalLoginEnabled(boolean v) { this.localLoginEnabled = v; }
    public boolean isRequireEmailVerified() { return requireEmailVerified; }
    public void setRequireEmailVerified(boolean v) { this.requireEmailVerified = v; }
    public List<String> getAllowedEmails() { return allowedEmails; }
    public void setAllowedEmails(List<String> v) { this.allowedEmails = v; }
    public List<String> getAllowedDomains() { return allowedDomains; }
    public void setAllowedDomains(List<String> v) { this.allowedDomains = v; }
    public List<String> getAllowedBitbucketWorkspaces() { return allowedBitbucketWorkspaces; }
    public void setAllowedBitbucketWorkspaces(List<String> v) { this.allowedBitbucketWorkspaces = v; }
    public String getBitbucketApiBase() { return bitbucketApiBase; }
    public void setBitbucketApiBase(String v) { this.bitbucketApiBase = v; }
    public List<String> getAdminEmails() { return adminEmails; }
    public void setAdminEmails(List<String> v) { this.adminEmails = v; }
    public List<String> getPkceProviders() { return pkceProviders; }
    public void setPkceProviders(List<String> v) { this.pkceProviders = v; }
    public String getCspMode() { return cspMode; }
    public void setCspMode(String v) { this.cspMode = v; }
    public int getMaxLoginAttempts() { return maxLoginAttempts; }
    public void setMaxLoginAttempts(int v) { this.maxLoginAttempts = v; }
    public int getLockoutMinutes() { return lockoutMinutes; }
    public void setLockoutMinutes(int v) { this.lockoutMinutes = v; }
}
