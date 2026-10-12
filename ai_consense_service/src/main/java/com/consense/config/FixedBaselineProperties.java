package com.consense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit opt-in for the immutable development reference corpus, never a project adoption. */
@Component
@ConfigurationProperties(prefix = "consense.fixed-baseline")
public class FixedBaselineProperties {
    private boolean enabled;
    private String manifestPath;
    private String manifestSha256 = "deb87c356c2a724f71d8bc316d86c4272fa9ebe014fb2bedf4c18de29a425aab";
    private String baseUrl = "http://127.0.0.1:8876";
    private String projectId = "fixed-best-baseline-20261012-2100";
    private String indexSignature = "b0a7bc46bb241f9acbace0bab75a648c83db3ae339c79e68c4d689f1b635f64b";
    private long timeoutMs = 120000;
    private int candidates = 50;
    private int maxResponseBytes = 16777216;
    private int maxRecordBytes = 16777216;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getManifestPath() { return manifestPath; }
    public void setManifestPath(String v) { manifestPath = v; }
    public String getManifestSha256() { return manifestSha256; }
    public void setManifestSha256(String v) { manifestSha256 = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String v) { projectId = v; }
    public String getIndexSignature() { return indexSignature; }
    public void setIndexSignature(String v) { indexSignature = v; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long v) { timeoutMs = v; }
    public int getCandidates() { return candidates; }
    public void setCandidates(int v) { candidates = v; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int v) { maxResponseBytes = v; }
    public int getMaxRecordBytes() { return maxRecordBytes; }
    public void setMaxRecordBytes(int v) { maxRecordBytes = v; }
}
