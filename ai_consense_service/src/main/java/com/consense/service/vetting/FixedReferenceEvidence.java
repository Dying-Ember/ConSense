package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

/** Lossless, source-bound fixed reference. It carries no canonical/project-adoption claim. */
public final class FixedReferenceEvidence {
    private final String id;
    private final double score;
    private final JsonNode payload, tableArtifact, sourceUnit;
    FixedReferenceEvidence(String id, double score, JsonNode payload, JsonNode tableArtifact, JsonNode sourceUnit) {
        this.id = id; this.score = score; this.payload = payload.deepCopy();
        this.tableArtifact = tableArtifact == null ? null : tableArtifact.deepCopy();
        this.sourceUnit = sourceUnit == null ? null : sourceUnit.deepCopy();
    }
    public String getId() { return id; }
    public double getScore() { return score; }
    public String getContent() { return payload.path("content").asText(); }
    public String getFileLabel() { return payload.path("fileLabel").asText(payload.path("fileKey").asText()); }
    public int getPageNo() { return payload.path("pageNo").asInt(); }
    public String getSourceFamily() { return payload.path("sourceQuality").path("family").asText(); }
    public JsonNode getPayload() { return payload.deepCopy(); }
    public JsonNode getTableArtifact() { return tableArtifact == null ? null : tableArtifact.deepCopy(); }
    public JsonNode getSourceUnit() { return sourceUnit == null ? null : sourceUnit.deepCopy(); }
    public VectorStore.SearchHit asHit() {
        Map<String,Object> map = JsonUtils.mapper().convertValue(payload, new TypeReference<Map<String,Object>>() {});
        return new VectorStore.SearchHit(id, score, map);
    }
}
