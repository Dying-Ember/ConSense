package com.consense.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit opt-in; registration remains an internal implementation operation. */
@Data @Component @ConfigurationProperties(prefix="consense.oer-evidence")
public class CanonicalEvidenceProperties {
    private String baseUrl;
    /** Retrieval and explicit normal publication use module URLs; asset/snapshot reads use baseUrl. */
    private Map<String,String> retrievalBaseUrls=new LinkedHashMap<>();
    /** Reviewed sidecar identities keyed by exact canonical projection; absent remains text/model-input only. */
    private Map<String,String> sourcePageRenderEvidenceIdentities=new LinkedHashMap<>();
    private int timeoutMs=30000;
    private int maxSnapshotBytes=16777216;
    private int maxAssetBytes=33554432;
}
