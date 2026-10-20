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
    /** Whole normal-source publication builds and validates complete graphs before returning their sealed projection. */
    private int normalPublicationTimeoutMs=120000;
    /** Complete normal graphs expand into manifest, canonical and chunk copies; native contracts retain their own caps. */
    private int maxSnapshotBytes=1024*1024*1024;
    private int maxAssetBytes=33554432;
}
