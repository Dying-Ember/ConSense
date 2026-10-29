package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.vector.VectorStore.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Collectors;

/** A topic-local citation catalog. Fixed standards never become project documents or canonical evidence. */
final class FixedVettingReferences {
    static final String SOURCE_CLASS = "fixed_development_baseline_standard";
    private final FixedBaselineRetrievalClient client;
    private String projectId,selectionIdentity;
    private final Map<String, SearchHit> hits = new LinkedHashMap<>();
    private final Map<String, String> views = new LinkedHashMap<>();
    private final Set<String> slIds = new LinkedHashSet<>();

    private FixedVettingReferences(FixedBaselineRetrievalClient client, List<SearchHit> selected) {
        this.client = client;
        for (SearchHit hit : selected) {
            FixedReferenceEvidence evidence = client.evidence(hit);
            JsonNode payload = evidence.getPayload();
            Chunk view = JsonUtils.read(JsonUtils.write(payload), Chunk.class);
            if (!Objects.equals(hit.getId(), view.getId()) || !"standard".equals(view.getRole())
                    || view.getCanonicalEvidence() != null || view.getCanonicalPayload() != null)
                throw new IllegalArgumentException("Fixed reference must retain its own standard identity");
            view.setSourceClass(SOURCE_CLASS);
            view.setSourcePurpose("fixed_reference");
            if (hits.putIfAbsent(view.getId(), hit) != null)
                throw new IllegalArgumentException("Duplicate fixed reference ID");
            views.put(view.getId(), JsonUtils.write(view));
            if("SL".equals(evidence.getSourceFamily()))slIds.add(view.getId());
        }
    }

    static FixedVettingReferences empty() { return new FixedVettingReferences(null, Collections.emptyList()); }
    /** Carry only existing packet catalogs across a progressive round, without repeating retrieval. */
    static FixedVettingReferences combine(FixedVettingReferences before,FixedVettingReferences added){
        before.assertSelectionCurrent();added.assertSelectionCurrent();if(before.isEmpty())return added;if(added.isEmpty())return before;
        if(before.client!=added.client||!Objects.equals(before.projectId,added.projectId)||!Objects.equals(before.selectionIdentity,added.selectionIdentity))throw new IllegalArgumentException("Fixed reference editions differ across progressive rounds");
        // The constructor identity is retained through this existing client, while immutable view bytes are shared.
        FixedVettingReferences result=new FixedVettingReferences(before.client,Collections.emptyList());result.projectId=before.projectId;result.selectionIdentity=before.selectionIdentity;
        result.hits.putAll(before.hits);result.views.putAll(before.views);result.slIds.addAll(before.slIds);
        for(String id:added.hits.keySet()){if(result.views.containsKey(id)&&(!Objects.equals(result.views.get(id),added.views.get(id))||!before.pointer(id).equals(added.pointer(id))))throw new IllegalArgumentException("Fixed reference view or source identity changed across progressive rounds");result.hits.putIfAbsent(id,added.hits.get(id));result.views.putIfAbsent(id,added.views.get(id));}
        result.slIds.addAll(added.slIds);result.assertSelectionCurrent();return result;
    }
    static FixedVettingReferences retrieve(FixedBaselineRetrievalClient client,String projectId,String topic,int limit) {
        return retrieve(client,projectId,Collections.singletonList(topic),limit);
    }
    static FixedVettingReferences retrieve(FixedBaselineRetrievalClient client,String projectId,List<String> queries,int limit) {
        if(client==null||!client.enabled())return empty();
        String identity=JsonUtils.write(client.projectSelection(projectId));
        Map<String,SearchHit> selected=new LinkedHashMap<>();
        for(String query:queries){
            for(SearchHit hit:VettingReviewDirections.searchWithFallback(query,part->{
                if(!Objects.equals(identity,JsonUtils.write(client.projectSelection(projectId))))throw new IllegalStateException("Fixed reference editions changed during this review");
                return client.searchForProject(projectId,"vetting",part,limit);
            }))selected.putIfAbsent(hit.getId(),hit);
        }
        FixedVettingReferences result=new FixedVettingReferences(client,FixedReferenceEvidence.focusRequestedClauses(new ArrayList<>(selected.values()),String.join("\n",queries)));
        result.projectId=projectId;result.selectionIdentity=identity;result.assertSelectionCurrent();return result;
    }
    private void assertSelectionCurrent() {
        if(projectId!=null&&!Objects.equals(selectionIdentity,JsonUtils.write(client.projectSelection(projectId))))throw new IllegalStateException("Fixed reference editions changed during this review");
    }
    boolean isEmpty() { return hits.isEmpty(); }
    JsonNode pointer(String id) {SearchHit hit=hits.get(id);if(hit==null)throw new IllegalArgumentException("Fixed reference is outside this packet catalog");return client.evidence(hit).pointer();}
    static boolean isFixed(Chunk chunk) { return chunk != null && SOURCE_CLASS.equals(chunk.getSourceClass()); }

    List<Chunk> chunks() {
        return views.values().stream().map(json -> JsonUtils.read(json, Chunk.class)).collect(Collectors.toList());
    }
    List<Chunk> requestedGroups(String request) {
        List<Chunk> exact=new ArrayList<>();
        for(SearchHit hit:hits.values())if(FixedReferenceEvidence.matchesRequestedClause(hit,request))exact.add(JsonUtils.read(views.get(hit.getId()),Chunk.class));
        return completeGroups(exact);
    }
    List<Chunk> completeGroups(List<Chunk> selected) {
        Set<String> required=new LinkedHashSet<>();
        for(Chunk chunk:selected)if(isFixed(chunk)){
            SearchHit hit=hits.get(chunk.getId());if(hit==null)throw new IllegalArgumentException("Fixed source is outside this packet catalog");
            JsonNode unit=client.evidence(hit).getSourceUnit();List<String> members=new ArrayList<>();
            if(unit!=null)for(JsonNode id:unit.path("requiredMemberIds"))members.add(id.asText());
            if(members.isEmpty())members.add(hit.getId());
            if(!hits.keySet().containsAll(members))throw new IllegalArgumentException("Fixed retrieved group is incomplete");
            required.addAll(members);
        }
        return required.stream().map(id->JsonUtils.read(views.get(id),Chunk.class)).collect(Collectors.toList());
    }
    /** Only the verified source family can establish that a retrieved reference belongs to SL. */
    List<Chunk> slChunks() {
        return views.entrySet().stream().filter(entry->slIds.contains(entry.getKey())).map(entry->JsonUtils.read(entry.getValue(),Chunk.class)).collect(Collectors.toList());
    }
    List<Chunk> topicCorpus(List<Chunk> project) {
        List<Chunk> result = new ArrayList<>(project);
        Set<String> ids = project.stream().map(Chunk::getId).collect(Collectors.toSet());
        for (Chunk fixed : chunks()) {
            if (!ids.add(fixed.getId())) throw new IllegalArgumentException("Fixed and project citation IDs collide");
            result.add(fixed);
        }
        return result;
    }
    /** Preserve complete observed groups without conferring an adopted-clause or literal-origin status. */
    void admit(VettingContextBuilder.Selection selection, int maximumCharacters) {
        admit(selection,maximumCharacters,null);
    }
    void admit(VettingContextBuilder.Selection selection, int maximumCharacters,Set<String> activeIds) {
        Map<String,List<String>> groups = new LinkedHashMap<>();
        for (SearchHit hit : hits.values()) {
            if(activeIds!=null&&!activeIds.contains(hit.getId()))continue;
            JsonNode unit = client.evidence(hit).getSourceUnit();
            List<String> required = new ArrayList<>();
            if (unit != null) for (JsonNode id : unit.path("requiredMemberIds")) required.add(id.asText());
            if (required.isEmpty()) required.add(hit.getId());
            if (!hits.keySet().containsAll(required)) throw new IllegalArgumentException("Fixed retrieved group is incomplete");
            String key = unit == null ? hit.getId() : unit.path("unitId").asText(hit.getId());
            List<String> prior = groups.putIfAbsent(key, required);
            if (prior != null && !prior.equals(required)) throw new IllegalArgumentException("Fixed observed group member identities differ");
        }
        Set<String> present = selection.getChunks().stream().map(Chunk::getId).collect(Collectors.toSet());
        boolean tenderPresent = selection.getChunks().stream().anyMatch(c -> "tender".equals(c.getRole()));
        for (Map.Entry<String,List<String>> group : groups.entrySet()) {
            VettingContextBuilder.GroupTrace trace = new VettingContextBuilder.GroupTrace();
            trace.setId("fixed-reference:"+group.getKey());trace.setRelation("fixed_reference_observed_group");
            trace.setRelationStrength("retrieved_reference_project_adoption_unconfirmed");
            trace.setContextIds(new ArrayList<>(group.getValue()));trace.setScopeCoverage("complete_saved_observed_reference_only");
            selection.getGroups().add(trace);
            long added = 0;
            for (String id : group.getValue()) if (!present.contains(id)) added += JsonUtils.read(views.get(id),Chunk.class).getContent().length();
            if (!tenderPresent || added + (long)selection.getContentChars() > maximumCharacters) {
                trace.setStatus(tenderPresent ? "dropped_budget" : "not_submitted_without_tender");
                selection.getDroppedIds().addAll(group.getValue());continue;
            }
            for (String id : group.getValue()) if (present.add(id)) selection.getChunks().add(JsonUtils.read(views.get(id),Chunk.class));
            selection.setContentChars((int)(selection.getContentChars()+added));
            trace.setIncrementalChars((int)added);trace.setStatus("selected");trace.setFullySubmitted(true);
            selection.getDroppedIds().removeAll(group.getValue());
        }
    }
    private List<SearchHit> selectedHits(List<Chunk> selected) {
        List<SearchHit> result = new ArrayList<>();
        for (Chunk chunk : selected) {
            if (!isFixed(chunk)) {
                if (hits.containsKey(chunk.getId())) throw new IllegalArgumentException("Fixed reference lost its source class");
                continue;
            }
            if (!Objects.equals(views.get(chunk.getId()), JsonUtils.write(chunk)))
                throw new IllegalArgumentException("Fixed reference view changed or is outside this topic catalog");
            result.add(hits.get(chunk.getId()));
        }
        return result;
    }
    Map<String, Map<String, Object>> modelExcerpts(List<Chunk> selected) {
        assertSelectionCurrent();
        List<SearchHit> chosen = selectedHits(selected);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (chosen.isEmpty()) return result;
        // The shared projector checks complete parent/table groups; never serialize raw sourceUnits into the prompt.
        JsonNode material = JsonUtils.parse(client.toModelMaterial(chosen, Integer.MAX_VALUE));
        JsonNode references = material.path("references");
        if (!references.isArray()) throw new IllegalArgumentException("Fixed reference model material is missing");
        for (JsonNode reference : references) {
            String id = reference.path("id").asText();
            if (!hits.containsKey(id) || result.putIfAbsent(id, JsonUtils.readMap(JsonUtils.write(reference))) != null)
                throw new IllegalArgumentException("Fixed model reference IDs differ from the topic catalog");
        }
        Set<String> expected = chosen.stream().map(SearchHit::getId).collect(Collectors.toSet());
        if (!result.keySet().equals(expected)) throw new IllegalArgumentException("Fixed reference group was not returned completely");
        for (Map<String, Object> excerpt : result.values()) {
            excerpt.put("role", "standard");
            excerpt.put("evidenceBackend", "fixed_development_baseline");
            excerpt.put("projectApplicability", "unconfirmed_requires_real_project_adoption");
            excerpt.put("qualifiersComplete", "unknown");
            excerpt.put("semanticScopeVerified", false);
            excerpt.put("sourceQualityAccepted", false);
            excerpt.put("nativeRows", Collections.emptyList());
            excerpt.put("unresolvedNativeRowParts", Collections.emptyList());
        }
        return result;
    }
    void assertCurrent(List<Chunk> selected) {
        assertSelectionCurrent();
        List<SearchHit> chosen = selectedHits(selected);
        if (!chosen.isEmpty()) client.assertCurrent(chosen);
    }
    boolean quoted(List<VettingSemanticReview.Quote> quotes, Map<String, Chunk> submitted) {
        return quotes.stream().anyMatch(q -> isFixed(submitted.get(q.getChunkId())));
    }
}
