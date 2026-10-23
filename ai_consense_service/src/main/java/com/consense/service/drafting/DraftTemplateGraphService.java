package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.*;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Source-reviewed graph revisions and project adoption; the normal parser and document chain remain owners. */
@Service @RequiredArgsConstructor
public class DraftTemplateGraphService {
    private final ProjectService projects;
    private final SourceDocumentRepository sources;
    private final DraftTemplateGraphRevisionRepository revisions;
    private final DraftTemplateGraphHeadRepository heads;
    private final DraftGraphInputBindingRepository bindings;
    private final DraftVariableRepository variables;
    private final DraftExtractionRunRepository runs;
    private final DraftReviewSources sourceReader;
    private final AiGateway ai;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;

    public boolean hasConfirmed(String projectId) {
        return heads.findById(projectId).map(h->h.getCurrentRevisionId()!=null).orElse(false);
    }
    public boolean hasDraft(String projectId) {return heads.findById(projectId).map(h->h.getDraftRevisionId()!=null).orElse(false);}
    @Transactional public void lockForGeneration(String projectId) { lock(projectId); }
    public Map<String,Object> current(String projectId) {
        projects.require(projectId);DraftTemplateGraphHead head=head(projectId);
        Map<Long,JsonNode> checkedSources=new HashMap<>();
        return map("currentRevisionId",head.getCurrentRevisionId(),"draftRevisionId",head.getDraftRevisionId(),
                "current",head.getCurrentRevisionId()==null?null:revisionVO(requireRevision(projectId,head.getCurrentRevisionId()),true,checkedSources),
                "draft",head.getDraftRevisionId()==null?null:revisionVO(requireRevision(projectId,head.getDraftRevisionId()),true,checkedSources),"sources",sources(projectId));
    }
    public List<Map<String,Object>> history(String projectId) {
        projects.require(projectId);List<Map<String,Object>> result=new ArrayList<>();
        for(DraftTemplateGraphRevision revision:revisions.findByProjectIdOrderByCreatedAtDesc(projectId))result.add(revisionVO(revision,false));return result;
    }
    public Map<String,Object> revision(String projectId,String revisionId) { projects.require(projectId);return revisionVO(requireRevision(projectId,revisionId),true); }
    public List<Map<String,Object>> sources(String projectId) {
        projects.require(projectId);return JsonUtils.mapper().convertValue(sourceReader.sources(sources.findDraftReviewSourcesByProjectId(projectId)),List.class);
    }
    public JsonNode reading(String projectId,Long sourceId) { return sourceReader.reading(requireSource(projectId,sourceId)); }
    public Map<String,Object> bootstrap(String projectId) {
        projects.require(projectId);List<Map<String,Object>> available=templateIdentities(projectId);
        Map<String,Object> graph=map("kind","consense-template-graph-v1","graph",map("variables",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",1,"blank",true,"guidanceMode","minimum"),"sources",available,"selectedDocuments",selected(available));
        graph=DraftReviewDiscovery.seed(graph,templateReadings(projectId));
        return map("graph",graph,"sources",available,"currentRevisionId",head(projectId).getCurrentRevisionId(),"draftRevisionId",head(projectId).getDraftRevisionId());
    }
    public Map<String,Object> discover(String projectId,String revisionId,String expectedCurrent,int maxItems){
        if(maxItems<1||maxItems>10)throw new BizException(4007,"DISCOVERY_BUDGET_MUST_BE_1_TO_10");projects.require(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);
        if(!"DRAFT".equals(revision.getStatus())||!Objects.equals(head(projectId).getDraftRevisionId(),revisionId))throw conflict("Only the selected draft can receive discovery suggestions.");expected(head(projectId).getCurrentRevisionId(),expectedCurrent);requireSourcesCurrent(revision);
        Map<String,Object> graph=DraftReviewDiscovery.discover(ai,JsonUtils.readMap(revision.getGraphJson()),templateReadings(projectId),maxItems);
        return new TransactionTemplate(transactionManager).execute(status->{lockedHead(projectId);requireSourcesCurrent(revision);return save(projectId,graph,expectedCurrent,revisionId,true);});
    }
    @Transactional
    public Map<String,Object> save(String projectId,Map<String,Object> proposed,String expectedCurrent) { return save(projectId,proposed,expectedCurrent,null,false); }
    @Transactional
    public Map<String,Object> save(String projectId,Map<String,Object> proposed,String expectedCurrent,String expectedDraft,boolean checkDraft) {
        DraftTemplateGraphHead head=lockedHead(projectId);expected(head.getCurrentRevisionId(),expectedCurrent);
        if(checkDraft)expected(head.getDraftRevisionId(),expectedDraft);
        Map<String,Object> graph=pinGraph(projectId,proposed);DraftReviewGraph.validate(graph);
        for(Map<String,Object> node:DraftReviewGraph.nodes(graph))if("contractTitle".equals(id(node)))throw new BizException(4007,"PROJECT_IDENTITY_KEY_RESERVED: Use the fixed project identity input.");
        List<Object> archived=archives(projectId,graph);
        DraftTemplateGraphRevision saved=create(projectId,head.getDraftRevisionId()==null?head.getCurrentRevisionId():head.getDraftRevisionId(),"DRAFT",graph,archived);
        head.setDraftRevisionId(saved.getId());heads.save(head);return revisionVO(saved,true);
    }
    @Transactional
    public Map<String,Object> confirm(String projectId,String draftId,String expectedCurrent) {
        DraftTemplateGraphHead head=lockedHead(projectId);expected(head.getCurrentRevisionId(),expectedCurrent);
        DraftTemplateGraphRevision draft=requireRevision(projectId,draftId);
        if(!"DRAFT".equals(draft.getStatus())||!Objects.equals(head.getDraftRevisionId(),draftId))throw conflict("Only the current saved draft can be confirmed.");
        requireSourcesCurrent(draft);Map<String,Object> graph=JsonUtils.readMap(draft.getGraphJson());DraftReviewGraph.validate(graph);
        int active=0;for(Object raw:list(state(graph).get("variables"))) {
            Map<String,Object> node=asMap(raw);if("excluded".equals(node.get("status")))continue;
            active++;
            if(!"confirmed".equals(node.get("status")))throw new BizException(4007,"GRAPH_REVIEW_INCOMPLETE: "+node.get("id"));
            requireReviewedSource(projectId,graph,node);
            if(Boolean.TRUE.equals(asMap(node.get("correction")).get("needsRebind")))throw new BizException(4007,"GRAPH_RULE_REBIND_REQUIRED: "+node.get("id"));
            for(Object rawTarget:list(node.get("editTargets"))) {
                Map<String,Object> target=asMap(rawTarget);String action=string(target.get("action"));
                if(Boolean.TRUE.equals(target.get("needsRebind"))||"check".equals(action))throw new BizException(4007,"GRAPH_TARGET_REVIEW_REQUIRED: "+node.get("id"));
                if(Arrays.asList("replace","fill").contains(action)&&!DraftReviewGraph.hasReplacementPlacement(target)||"insert".equals(action)&&!DraftReviewGraph.hasInsertionPoint(target))throw new BizException(4007,"GRAPH_REPLACEMENT_PLACEMENT_REQUIRED: "+node.get("id"));
            }
        }
        if(active==0)throw new BizException(4007,"GRAPH_HAS_NO_REVIEWED_ITEMS");
        DraftTemplateGraphRevision confirmed=create(projectId,draftId,"CONFIRMED",graph,JsonUtils.readList(draft.getSourcesJson(),Object.class));
        head.setCurrentRevisionId(confirmed.getId());heads.save(head);ensureInputs(projectId,confirmed,graph);restoreDraftCandidates(projectId,confirmed,graph);return revisionVO(confirmed,true);
    }
    public Map<String,Object> graph(String projectId) { return JsonUtils.readMap(workingRevision(projectId).getGraphJson()); }
    public SourceDocument selectedTemplate(String projectId,String fileKey){DraftTemplateGraphRevision revision=workingRevision(projectId);requireSourcesCurrent(revision);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());String selected=string(asMap(graph.get("selectedDocuments")).get(fileKey));
        if(selected==null)throw new BizException(4011,"GRAPH_TEMPLATE_NOT_SELECTED: "+fileKey);SourceDocument source;try{source=requireSource(projectId,Long.valueOf(selected));}catch(NumberFormatException invalid){throw new BizException(4007,"GRAPH_TEMPLATE_ID_INVALID");}
        if(!SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(source.getCategory())||!fileKey.equals(source.getFileKey()))throw conflict("Selected source category or family changed.");return source;}
    public Map<String,Object> archivedSource(String projectId,String revisionId,String sourceId){projects.require(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);for(JsonNode manifest:JsonUtils.parse(revision.getSourcesJson()))if(sourceId.equals(manifest.path("id").asText())||sourceId.equals(manifest.path("sourceDocumentId").asText()))return map("fileName",manifest.path("fileName").asText(),"format",manifest.path("format").asText(),"bytes",sourceReader.archivedBytes(manifest));throw new BizException(4011,"GRAPH_REVISION_SOURCE_NOT_FOUND");}
    public Map<String,Object> candidates(String projectId,String revisionId) {
        projects.require(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson()),trace=latestCandidateTrace(projectId,revision,graph);Map<String,Object> found=asMap(trace.get("candidatesByKey"));List<VariableVO> result=new ArrayList<>();
        for(Map<String,Object> field:extractionFields(graph)){List<CandidateVO> values=Objects.equals(DraftReviewGraph.fieldHash(field),asMap(trace.get("fieldDefinitions")).get(id(field)))?JsonUtils.readList(JsonUtils.write(list(found.get(id(field)))),CandidateVO.class):Collections.emptyList();result.add(candidateVariableVO(revision,field,values));}
        Set<String> activeKeys=new HashSet<>();for(VariableVO variable:result)if(!variable.getCandidates().isEmpty())activeKeys.add(variable.getKey());
        List<VariableVO> unassigned=new ArrayList<>();for(Map<String,Object> old:DraftReviewGraph.nodes(graph))if(!activeKeys.contains(id(old))&&!list(found.get(id(old))).isEmpty()) {
            VariableVO retained=candidateVariableVO(revision,old,JsonUtils.readList(JsonUtils.write(list(found.get(id(old)))),CandidateVO.class));retained.setReviewRequired(true);unassigned.add(retained);
        }
        return map("runId",trace.get("runId"),"graphRevisionId",revisionId,"evidenceRevision",trace.get("evidenceRevision"),"stale",!trace.isEmpty()&&!Objects.equals(trace.get("evidenceRevision"),evidenceRevision(projectId)),"variables",result,"unassignedVariables",unassigned,"catalog",DraftReviewGraph.catalog(graph),"candidateDefinitions",candidateDefinitions(projectId,found));
    }
    private Map<String,Object> candidateDefinitions(String projectId,Map<String,Object> found) {
        Map<String,Set<String>> requested=new LinkedHashMap<>();
        for(Object raw:found.values())for(Object entry:list(raw)){Map<String,Object> candidate=asMap(entry);String revision=string(candidate.get("graphRevisionId")),hash=string(candidate.get("fieldDefinitionHash"));if(revision!=null&&hash!=null)requested.computeIfAbsent(revision,key->new HashSet<>()).add(hash);}
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<String,Set<String>> entry:requested.entrySet()){
            DraftTemplateGraphRevision original=revisions.findById(entry.getKey()).filter(value->projectId.equals(value.getProjectId())).orElse(null);if(original==null)continue;
            Map<String,Object> graph=JsonUtils.readMap(original.getGraphJson());Map<String,String> hashes=new HashMap<>();
            for(Map<String,Object> field:extractionFields(graph)){String hash=DraftReviewGraph.fieldHash(field);if(entry.getValue().contains(hash))hashes.put(id(field),hash);}
            for(Object raw:list(DraftReviewGraph.catalog(graph).get("groups")))for(Object field:list(asMap(raw).get("fields"))){String hash=hashes.get(string(asMap(field).get("key")));if(hash!=null)result.put(hash,field);}
        }
        return result;
    }
    @Transactional
    public Map<String,Object> correctCandidate(String projectId,String revisionId,String key,Map<String,Object> body) {
        DraftTemplateGraphHead head=lockedHead(projectId);DraftTemplateGraphRevision revision=requireRevision(projectId,revisionId);
        if(!revisionId.equals(head.getCurrentRevisionId())&&!revisionId.equals(head.getDraftRevisionId()))throw conflict("Review revision is no longer selected.");requireSourcesCurrent(revision);
        Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson()),trace=latestCandidateTrace(projectId,revision,graph);expected(string(trace.get("runId")),string(body.get("expectedRunId")));
        if(trace.isEmpty()||!Objects.equals(trace.get("evidenceRevision"),evidenceRevision(projectId)))throw conflict("Candidate evidence changed; identify again.");
        Map<String,Object> found=copy(asMap(trace.get("candidatesByKey")));List<Object> origin=new ArrayList<>(list(found.get(key)));Object rawIndex=body.get("candidateIndex");
        if(!(rawIndex instanceof Number)||((Number)rawIndex).doubleValue()!=((Number)rawIndex).intValue())throw new BizException(4007,"CANDIDATE_INDEX_REQUIRED");int index=((Number)rawIndex).intValue();if(index<0||index>=origin.size())throw conflict("Candidate no longer exists.");
        String target=body.get("targetKey")==null?key:string(body.get("targetKey"));Map<String,Object> targetField=field(graph,target);CandidateVO old=JsonUtils.mapper().convertValue(origin.get(index),CandidateVO.class);verifyCandidateSource(projectId,old);
        String quote=body.containsKey("sourceQuote")?string(body.get("sourceQuote")):old.getSourceQuote();SourceDocument source=requireSource(projectId,old.getSourceDocumentId());
        if(quote==null||quote.trim().isEmpty()||!source.getTextContent().contains(quote))throw new BizException(4007,"CANDIDATE_QUOTE_NOT_IN_SOURCE");
        Object raw=body.get("value");String normalized=normalizeCandidate(targetField,raw==null?"":raw instanceof String?(String)raw:JsonUtils.write(raw));
        Map<String,Object> changed=JsonUtils.mapper().convertValue(old,Map.class);changed.put("value",normalized);changed.put("sourceQuote",quote);changed.put("graphRevisionId",revisionId);changed.put("fieldDefinitionHash",DraftReviewGraph.fieldHash(targetField));changed.put("reason",body.getOrDefault("reason","Human-corrected candidate; not adopted."));
        origin.remove(index);found.put(key,origin);List<Object> destination=new ArrayList<>(list(found.get(target)));destination.add(changed);found.put(target,destination);
        String runId=UUID.randomUUID().toString();Map<String,Object> corrected=copy(trace);corrected.put("runId",runId);corrected.put("graphRevisionId",revisionId);corrected.put("graphSha256",revision.getGraphSha256());corrected.put("parentRunId",trace.get("runId"));corrected.put("actor","manual_correction");corrected.put("candidatesByKey",found);corrected.put("finishedAt",Instant.now().toString());
        Map<String,Object> definitions=new LinkedHashMap<>(asMap(trace.get("fieldDefinitions")));if(destination.stream().allMatch(value->Objects.equals(DraftReviewGraph.fieldHash(targetField),asMap(value).get("fieldDefinitionHash"))))definitions.put(target,DraftReviewGraph.fieldHash(targetField));else definitions.remove(target);corrected.put("fieldDefinitions",definitions);
        runs.save(new DraftExtractionRun(runId,projectId,Instant.now(),JsonUtils.write(corrected)));
        if(revisionId.equals(head.getCurrentRevisionId())){ensureInputs(projectId,revision,graph);for(String affected:new LinkedHashSet<>(Arrays.asList(key,target))){if(extractionFields(graph).stream().noneMatch(item->affected.equals(id(item))))continue;DraftVariable variable=variable(projectId,affected);boolean definitionMatches=Objects.equals(DraftReviewGraph.fieldHash(field(graph,affected)),definitions.get(affected));variable.setCandidatesJson(JsonUtils.write(definitionMatches?list(found.get(affected)):Collections.emptyList()));if(!definitionMatches||Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited()))variable.setReviewRequired(true);variables.save(variable);if(!"contractTitle".equals(affected)){DraftGraphInputBinding binding=binding(projectId,affected);binding.setCandidateRevisionId(revisionId);bindings.save(binding);}}}
        return candidates(projectId,revisionId);
    }
    private Map<String,Object> latestCandidateTrace(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){
        return findCandidateTrace(projectId,trace->{
            if(!(trace.get("candidatesByKey") instanceof Map))return false;
            if(Objects.equals(revision.getId(),trace.get("graphRevisionId")))return true;
            for(Map<String,Object> old:DraftReviewGraph.nodes(graph))if(!list(asMap(trace.get("candidatesByKey")).get(id(old))).isEmpty())return true;
            for(Map<String,Object> field:DraftReviewGraph.fields(graph))if(Objects.equals(DraftReviewGraph.fieldHash(field),asMap(trace.get("fieldDefinitions")).get(id(field))))return true;
            return false;
        });
    }
    private Map<String,Object> findCandidateTrace(String projectId,java.util.function.Predicate<Map<String,Object>> accepts){
        for(int page=0;;page++){
            List<DraftExtractionRun> batch=runs.findByProjectIdOrderByFinishedAtDescIdDesc(projectId,org.springframework.data.domain.PageRequest.of(page,1));
            if(batch.isEmpty())return Collections.emptyMap();
            Map<String,Object> trace=JsonUtils.readMap(batch.get(0).getTraceJson());
            if(Arrays.asList("completed","partial").contains(trace.get("status"))&&accepts.test(trace))return trace;
        }
    }
    public Map<String,Object> catalog(String projectId) {
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> result=new LinkedHashMap<>(DraftReviewGraph.catalog(JsonUtils.readMap(revision.getGraphJson())));
        if(overrideField(JsonUtils.readMap(revision.getGraphJson()))!=null){List<Object> system=new ArrayList<>(list(result.get("systemFields")));system.add(map("key","targetOverrides","hidden",true,"kind","list","label",LocalizedText.same("Target amendments")));result.put("systemFields",system);}
        result.put("graphRevisionId",revision.getId());result.put("reviewStatus",revision.getStatus());return result;
    }
    @Transactional
    public List<VariableVO> variables(String projectId) {
        lockedHead(projectId);
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());ensureInputs(projectId,revision,graph);
        if("DRAFT".equals(revision.getStatus()))restoreDraftCandidates(projectId,revision,graph);
        boolean sourceStale=stale(revision);
        List<VariableVO> result=new ArrayList<>();for(Map<String,Object> field:allFields(graph))result.add(variableVO(projectId,revision,field,sourceStale));return result;
    }
    @Transactional
    public VariableVO updateVariable(String projectId,String key,VariablePatch patch) {
        lockedHead(projectId);DraftTemplateGraphRevision revision=workingRevision(projectId);requireSourcesCurrent(revision);
        Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson()),field="targetOverrides".equals(key)?overrideField(graph):field(graph,key),body=JsonUtils.mapper().convertValue(patch,Map.class);
        if(field==null)throw new BizException(4007,"Unknown reviewed drafting input: "+key);
        expected(revision.getId(),string(body.get("graphRevisionId")));expected(DraftReviewGraph.fieldHash(field),string(body.get("fieldDefinitionHash")));
        ensureInputs(projectId,revision,graph);DraftVariable variable=variable(projectId,key);DraftGraphInputBinding binding=binding(projectId,key);
        String raw=patch.getValue()!=null?patch.getValue():patch.getChoice();boolean explicitEmpty=Boolean.TRUE.equals(patch.getExplicitEmpty());
        if(explicitEmpty&&(!"text".equals(asMap(field.get("type")).get("kind"))||!"".equals(raw)||patch.getCandidateIndex()!=null))throw new BizException(4007,"EXPLICIT_EMPTY_TEXT_REQUIRED");List<CandidateVO> candidates=DraftAdoption.candidates(variable);
        if(patch.getCandidateIndex()!=null) {
            int index=patch.getCandidateIndex();if(index<0||index>=candidates.size())throw conflict("Candidate no longer exists.");
            CandidateVO candidate=candidates.get(index);
            if(patch.getCandidateSnapshot()==null||!JsonUtils.write(candidate).equals(JsonUtils.write(patch.getCandidateSnapshot())))throw conflict("Candidate changed since it was displayed.");
            if(!revision.getId().equals(binding.getCandidateRevisionId()))throw conflict("Candidate belongs to another graph revision; identify again.");
            verifyCandidateSource(projectId,candidate);raw=candidate.getValue();variable.setManuallyEdited(false);variable.setSourceRef(candidate.getSourceQuote());
            variable.setAdoptedSourcesJson(JsonUtils.write(Collections.singletonList(candidate)));binding.setAdoptionOrigin("candidate");binding.setAdoptionReason(null);
        } else if(raw!=null) {
            variable.setManuallyEdited(!JsonUtils.isBlankText(raw)||explicitEmpty);variable.setSourceRef(null);variable.setAdoptedSourcesJson("[]");binding.setAdoptionOrigin("manual");
            binding.setAdoptionReason(string(body.get("reason"))==null?patch.getNote():string(body.get("reason")));
            if("targetOverrides".equals(key)&&JsonUtils.isBlankText(binding.getAdoptionReason()))binding.setAdoptionReason("Explicitly adopted target amendment.");
            if((!JsonUtils.isBlankText(raw)||explicitEmpty)&&JsonUtils.isBlankText(binding.getAdoptionReason()))throw new BizException(4007,"MANUAL_REASON_REQUIRED");
        } else if(Boolean.TRUE.equals(patch.getReviewed())||Boolean.TRUE.equals(patch.getConfirmed())) {
            SuggestionSnapshot displayed=patch.getSuggestionSnapshot();
            if(displayed==null||!Objects.equals(displayed.getValue(),variable.getValueText())||!Objects.equals(displayed.getReviewRequired(),variable.getReviewRequired())||!JsonUtils.write(displayed.getCandidates()).equals(JsonUtils.write(candidates)))throw conflict("Suggestion changed since it was displayed.");
            for(CandidateVO candidate:DraftAdoption.adoptedSources(variable))verifyCandidateSource(projectId,candidate);
            raw=variable.getValueText();explicitEmpty="text".equals(asMap(field.get("type")).get("kind"))&&"".equals(raw)&&Boolean.TRUE.equals(variable.getConfirmed());
        }
        if(raw!=null) {
            String normalized="targetOverrides".equals(key)?normalizeOverrides(revision,graph,raw,variable.getValueText()):DraftReviewGraph.normalize(field,raw);variable.setValueText(normalized);variable.setConfirmed(!JsonUtils.isBlankText(normalized)||explicitEmpty);
            variable.setReviewRequired(false);variable.setConfirmedFrom("INPUT");binding.setAdoptedRevisionId(revision.getId());binding.setAdoptedDefinitionSha256(DraftReviewGraph.fieldHash(field));
        } else if(Boolean.FALSE.equals(patch.getConfirmed())) { variable.setConfirmed(false);variable.setManuallyEdited(false); }
        if(patch.getNote()!=null)variable.setNoteText(patch.getNote());variable.setUpdatedAt(Instant.now());variables.save(variable);bindings.save(binding);return variableVO(projectId,revision,field);
    }
    @Transactional
    public List<VariableVO> confirmAll(String projectId) {
        lockedHead(projectId);DraftTemplateGraphRevision revision=workingRevision(projectId);requireSourcesCurrent(revision);
        // Bulk confirmation can preserve existing adoptions only; displaying a list is not consent to candidate values.
        return variables(projectId);
    }
    public Map<String,Object> plan(String projectId,Map<String,String> patches) {
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());Map<String,Object> adopted=effective(projectId,revision,graph),visible=visibleValues(projectId,graph);
        if(patches!=null)for(Map.Entry<String,String> patch:patches.entrySet()){if("targetOverrides".equals(patch.getKey()))continue;Map<String,Object> field=field(graph,patch.getKey());String normalized=DraftReviewGraph.normalize(field,patch.getValue());Object value=decode(field,normalized);visible.put(patch.getKey(),value);if(JsonUtils.isBlankText(normalized))adopted.remove(patch.getKey());else adopted.put(patch.getKey(),value);}
        List<String> sourceReviewRequired=new ArrayList<>();
        if("DRAFT".equals(revision.getStatus()))for(Map<String,Object> node:DraftReviewGraph.nodes(graph))if("confirmed".equals(node.get("status"))) {
            try{requireReviewedSource(projectId,graph,node);}catch(BizException missing){node.put("status","pending");sourceReviewRequired.add(id(node));}
        }
        Map<String,Object> result=new LinkedHashMap<>(DraftReviewGraph.plan(graph,adopted,true));result.put("inputValues",visible);
        if("CONFIRMED".equals(revision.getStatus()))applyOverrides(projectId,revision,graph,result,patches);
        else if(!JsonUtils.isBlankText(string(visible.get("targetOverrides")))&&!"[]".equals(string(visible.get("targetOverrides"))))addIssue(result,"draft_target_overrides","请先在图谱中核对修改规则；未完成草稿不执行脱离规则的目标覆盖。 / Review the amendment in the graph; target overrides are not applied to an unconfirmed graph.");
        for(String id:sourceReviewRequired)addIssue(result,"source_review:"+id,"原文依据尚未核实，相关修改保留原文。 / Source evidence needs review; related passages remain unchanged.");
        DraftReviewGraph.deferConflictingActions(result);
        result.put("graphRevisionId",revision.getId());result.put("ruleVersion",revision.getGraphSha256());result.put("reviewStatus",revision.getStatus());
        result.put("snapshotId",snapshotId(projectId,patches));result.put("savedSnapshotId",snapshotId(projectId,null));result.put("preview",patches!=null);
        result.put("sourceIdentities",JsonUtils.readMap(revision.getGraphJson()).get("sources"));result.put("evidenceRevision",evidenceRevision(projectId));
        if(stale(revision))addIssue(result,"graph_source_changed","Template source changed; review and confirm a new graph.");return result;
    }
    public String snapshotId(String projectId,Map<String,String> patches) {
        DraftTemplateGraphRevision revision=workingRevision(projectId);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());
        List<Object> inputs=new ArrayList<>();for(Map<String,Object> field:allFields(graph)){DraftVariable variable=variables.findByProjectIdAndVarKey(projectId,id(field)).orElse(null);DraftGraphInputBinding binding=bindings.findById(bindingId(projectId,id(field))).orElse(null);inputs.add(Arrays.asList(id(field),variable==null?null:variable.getValueText(),variable==null?null:variable.getConfirmed(),variable==null?null:variable.getReviewRequired(),binding==null?null:binding.getAdoptedDefinitionSha256(),variable==null?null:variable.getAdoptedSourcesJson()));}
        return hash(map("graphRevisionId",revision.getId(),"graphSha256",revision.getGraphSha256(),"sourceCurrent",!stale(revision),"evidenceRevision",evidenceRevision(projectId),"inputs",inputs,"preview",patches));
    }
    public DocxEditResult compile(byte[] original,String fileKey,Map<String,Object> prepared,Map<String,String> bookmarks,NativeLayoutPlan layout) {
        String revisionId=string(prepared.get("graphRevisionId"));DraftTemplateGraphRevision revision=revisions.findById(revisionId).orElseThrow(()->conflict("The graph revision is unavailable."));
        if(!Objects.equals(workingRevision(revision.getProjectId()).getId(),revisionId))throw conflict("Graph changed during generation.");requireSourcesCurrent(revision);
        return DraftReviewGraph.compile(original,fileKey,JsonUtils.readMap(revision.getGraphJson()),prepared,bookmarks,layout,!list(prepared.get("unresolved")).isEmpty());
    }

    /** Model execution is outside project write locks; only the final matching graph/source result may publish candidates. */
    public List<VariableVO> extract(String projectId) { return extract(projectId,null); }
    public List<VariableVO> extract(String projectId,String requestedRevisionId) {
        return extract(projectId,requestedRevisionId,new ExtractionMonitor(){});
    }
    public interface ExtractionMonitor {
        default boolean beforeCall(String system,String user,String fileName) { return true; }
        default void completed(String raw) { }
        default void failed(RuntimeException failure) { }
        default void finished(boolean partial) { }
    }
    public List<VariableVO> extract(String projectId,String requestedRevisionId,ExtractionMonitor monitor) {
        projects.require(projectId);DraftTemplateGraphRevision revision=requestedRevisionId==null?workingRevision(projectId):requireRevision(projectId,requestedRevisionId);
        boolean draft="DRAFT".equals(revision.getStatus());if(draft&&!Objects.equals(head(projectId).getDraftRevisionId(),revision.getId()))throw conflict("Draft changed before extraction.");
        requireSourcesCurrent(revision);Map<String,Object> graph=JsonUtils.readMap(revision.getGraphJson());
        List<Map<String,Object>> fields=extractionFields(graph);String evidence=evidenceRevision(projectId),runId=UUID.randomUUID().toString();
        if(fields.isEmpty())throw new BizException(4007,"GRAPH_HAS_NO_INPUT_DEFINITIONS: Add source-bound candidate questions before extracting project values.");
        Map<String,List<CandidateVO>> found=new LinkedHashMap<>();String system="Extract only source-supported project candidate values for the supplied reviewed fields. Templates are not project facts. Return only a strict JSON array of {key,value,sourceQuote,reason,confidence}, without markdown fences. confidence must be a JSON number from 0 to 1, for example 0.90; never use strings or labels such as high/medium/low. key must exactly match a supplied field id. Match value to type.kind: boolean uses JSON true/false, number uses a finite JSON number, and choice uses an exact options[].value. sourceQuote must copy the exact supporting passage from this project correspondence. Unknown is null, never false or an empty collection. A project decision requires an explicit correspondence statement; do not infer a clause keep/delete decision only from a template rule. Omit fields without a supported non-null answer; do not emit null placeholder rows. Do not adopt values.";
        List<List<Map<String,Object>>> fieldBatches=DraftReviewGraph.extractionDefinitionBatches(fields);List<String> fieldPrompts=new ArrayList<>();for(List<Map<String,Object>> batch:fieldBatches)fieldPrompts.add(system+" Reviewed fields: "+JsonUtils.write(batch));
        ExtractTraceVO trace=new ExtractTraceVO(ai.chatModel(),null,system,null,new ArrayList<>());trace.setRunId(runId);trace.setHarnessVersion("reviewed-graph-v3-partial-typed-confidence");trace.setEvidenceRevision(evidence);trace.setStartedAt(Instant.now().toString());trace.setModelIdentity(ai.modelIdentity());
        Map<String,Object> definitions=new LinkedHashMap<>();for(Map<String,Object> field:fields)definitions.put(id(field),DraftReviewGraph.fieldHash(field));
        Map<String,Object> traceExtra=map("graphRevisionId",revision.getId(),"graphSha256",revision.getGraphSha256(),"fieldDefinitions",definitions);traceExtra.put("fieldPromptBatches",fieldPrompts);List<String> prompts=new ArrayList<>();
        try {
            int totalParts=0,consecutiveFailures=0;boolean interrupted=false;parts:for(SourceDocument source:correspondence(projectId)) {
                if(!"PARSED".equals(source.getParseStatus())||JsonUtils.isBlankText(source.getTextContent()))continue;
                String originalSha=sourceReader.archive(source).path("sourceSha256").asText();int start=0,index=0;for(String chunk:DraftClauseRules.chunks(source.getTextContent(),3000)) {
                    ExtractionPartVO part=new ExtractionPartVO(source.getId()+":"+index,source.getId(),source.getFileName(),DraftAdoption.sourceHash(source),index++,chunk,new ArrayList<>());
                    part.setContext(DraftSourceContext.primary(source.getTextContent(),start,start+chunk.length()));start+=chunk.length();trace.getParts().add(part);totalParts++;
                    String user="Project correspondence "+source.getFileName()+"\n"+part.getContext().getSourceText();prompts.add(user);
                    for(int batchIndex=0;batchIndex<fieldPrompts.size();batchIndex++){String requestSystem=fieldPrompts.get(batchIndex);Set<String> requestedKeys=new HashSet<>();for(Map<String,Object> requested:fieldBatches.get(batchIndex))requestedKeys.add(id(requested));
                    ExtractionAttemptVO attempt=new ExtractionAttemptVO(batchIndex,"primary-fields-"+batchIndex,null,user,null,"running",null);part.getAttempts().add(attempt);
                    String raw;
                    if(requestSystem.length()+user.length()>64000){attempt.setStatus("unexecuted");attempt.setErrorCode("request_budget_exceeded");interrupted=true;break parts;}
                    if(!monitor.beforeCall(requestSystem,user,source.getFileName())){attempt.setStatus("unexecuted");attempt.setErrorCode("cancelled_or_budget_reached");interrupted=true;break parts;}
                    try {raw=ai.complete(requestSystem,user);consecutiveFailures=0;monitor.completed(raw);attempt.setRawResponse(raw);trace.getRawResponses().add(raw);}
                    catch(RuntimeException failed){monitor.failed(failed);if(!DraftExtractionHarness.recordFailure(trace,attempt,failed)){if(++consecutiveFailures>=3){interrupted=true;break parts;}continue;}consecutiveFailures=0;raw=attempt.getRawResponse();}
                    try {
                        JsonNode items;
                        try {items=JsonUtils.parse(JsonUtils.extractJson(raw));if(!items.isArray())throw new BizException(4007,"Expected candidate array");}
                        catch(RuntimeException incomplete){items=DraftJsonItems.completePrefix(raw);attempt.setStatus("partial");if(attempt.getProviderFailureKind()==null)attempt.setErrorCode("incomplete_json");}
                        int itemIndex=0;
                        for(JsonNode item:items) {
                            String key=item.path("key").asText(),quote=item.path("sourceQuote").asText();Map<String,Object> field=optionalField(graph,key);JsonNode value=item.get("value");
                            ExtractionDecisionVO decision=new ExtractionDecisionVO(part.getPartId(),batchIndex,itemIndex++,key,value,null,quote,item.path("reason").asText(),item.path("confidence").isNumber()?item.path("confidence").asDouble():null,"rejected",new ArrayList<>());trace.getDecisions().add(decision);
                            if(field==null||!requestedKeys.contains(key)){decision.getCodes().add("unknown_key");continue;}if(value==null||value.isNull()){decision.setStatus("unanswered");continue;}
                            if(quote.trim().isEmpty()||!part.getContext().getSourceText().contains(quote)||!source.getTextContent().contains(quote)){decision.getCodes().add("quote_not_in_source");continue;}
                            Double confidence=decision.getConfidence();if(confidence==null||!Double.isFinite(confidence)||confidence<0.70||confidence>1){decision.getCodes().add("confidence_invalid");continue;}
                            if("context_insufficient".equals(part.getContext().getStopReason())&&Arrays.asList("list","record","multiselect").contains(asMap(field.get("type")).get("kind"))){decision.getCodes().add("context_incomplete");continue;}
                            try {String normalized=normalizeCandidate(field,value.isTextual()?value.asText():JsonUtils.write(value));if(JsonUtils.isBlankText(normalized)){decision.setStatus("unanswered");continue;}
                                Map<String,Object> candidate=map("value",normalized,"sourceDocumentId",source.getId(),"fileName",source.getFileName(),"sourceHash",DraftAdoption.sourceHash(source),"sourceQuote",quote,"reason",decision.getReason(),"confidence",confidence,"originalSha256",originalSha,"extractionRunId",runId,"graphRevisionId",revision.getId(),"fieldDefinitionHash",DraftReviewGraph.fieldHash(field));
                                found.computeIfAbsent(key,k->new ArrayList<>()).add(JsonUtils.mapper().convertValue(candidate,CandidateVO.class));decision.setNormalizedValue(normalized);decision.setStatus("accepted");
                            }catch(RuntimeException invalid){decision.getCodes().add("invalid_type");}
                        }if(!"partial".equals(attempt.getStatus()))attempt.setStatus("completed");
                    }catch(RuntimeException invalid){attempt.setStatus("failed");attempt.setErrorCode("invalid_json");}
                    }
                }
            }
            if(totalParts==0)throw new BizException(4005,"Upload successfully parsed project correspondence before extraction.");
            boolean partial=interrupted||trace.getParts().stream().flatMap(part->part.getAttempts().stream()).anyMatch(attempt->!"completed".equals(attempt.getStatus()));
            monitor.finished(partial);
            trace.setUserPrompt(String.join("\n\n",prompts));trace.setStatus(partial?"partial":"completed");trace.setFinishedAt(Instant.now().toString());
            if(partial){trace.setFailureCode(trace.getParts().stream().flatMap(part->part.getAttempts().stream()).anyMatch(attempt->"model_call_failed".equals(attempt.getErrorCode()))?"partial_source_failed":"incomplete_json");trace.setFailureMessage("Complete source-checked candidates remain available for manual review. Unfinished source parts require completion.");}
            return new TransactionTemplate(transactionManager).execute(status->{DraftTemplateGraphHead currentHead=lockedHead(projectId);String selected=draft?currentHead.getDraftRevisionId():currentHead.getCurrentRevisionId();if(!revision.getId().equals(selected)||!evidence.equals(evidenceRevision(projectId)))throw conflict("Graph or correspondence changed during extraction; existing adoptions were retained.");
                traceExtra.put("candidatesByKey",found);saveTrace(projectId,trace,traceExtra);
                if(draft){List<VariableVO> proposed=new ArrayList<>();for(Map<String,Object> field:fields)proposed.add(candidateVariableVO(revision,field,found.getOrDefault(id(field),Collections.emptyList())));return proposed;}
                ensureInputs(projectId,revision,graph);for(Map<String,Object> field:fields){String key=id(field);DraftVariable variable=variable(projectId,key);List<CandidateVO> previous=DraftAdoption.candidates(variable),latest=found.getOrDefault(key,Collections.emptyList());boolean changed=!candidateIdentity(previous).equals(candidateIdentity(latest));
                    if(partial&&latest.isEmpty()&&!previous.isEmpty())variable.setReviewRequired(true);
                    else {variable.setCandidatesJson(JsonUtils.write(latest));if(Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited())){if(changed)variable.setReviewRequired(true);}else{Set<String> values=new LinkedHashSet<>();for(CandidateVO candidate:latest)values.add(candidate.getValue());variable.setValueText(values.size()==1?values.iterator().next():"");variable.setSourceRef(latest.isEmpty()?null:latest.get(0).getSourceQuote());variable.setConfirmed(false);variable.setManuallyEdited(false);}}
                    variable.setUpdatedAt(Instant.now());variables.save(variable);if(!"contractTitle".equals(key)){DraftGraphInputBinding binding=binding(projectId,key);binding.setCandidateRevisionId(revision.getId());bindings.save(binding);}}
                return variables(projectId);});
        }catch(RuntimeException failure){trace.setStatus("failed");trace.setFinishedAt(Instant.now().toString());trace.setUserPrompt(String.join("\n\n",prompts));trace.setFailureCode("graph_extraction_failed");trace.setFailureMessage(failure.getMessage());new TransactionTemplate(transactionManager).execute(status->{saveTrace(projectId,trace,traceExtra);return null;});throw failure;}
    }

    private void saveTrace(String projectId,ExtractTraceVO trace,Map<String,Object> extra){Map<String,Object> payload=JsonUtils.mapper().convertValue(trace,Map.class);payload.putAll(extra);runs.save(new DraftExtractionRun(trace.getRunId(),projectId,Instant.now(),JsonUtils.write(payload)));}

    /** One source read per worker preparation; the compact identities bind the eventual single save. */
    Map<String,Object> automationInput(String projectId,String expectedCurrent,String expectedDraft,List<JsonNode> readings) {
        projects.require(projectId);DraftTemplateGraphHead selected=head(projectId);
        expected(selected.getCurrentRevisionId(),expectedCurrent);expected(selected.getDraftRevisionId(),expectedDraft);
        List<Map<String,Object>> identities=new ArrayList<>();
        for(SourceDocument source:templateSources(projectId)) {
            JsonNode reading=sourceReader.reading(source);readings.add(reading);
            Map<String,Object> identity=new LinkedHashMap<>();
            for(String key:Arrays.asList("id","sourceDocumentId","category","fileKey","fileName","parseStatus","sourceSha256","sourceHash","format","canEdit","docId","editionId","coverage","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus","editableCoordinates"))
                if(reading.has(key))identity.put(key,JsonUtils.mapper().convertValue(reading.get(key),Object.class));
            identities.add(identity);
        }
        if(identities.isEmpty())throw new BizException(4007,"请先上传并识别模板文件，再开始识别变量。");
        String baseId=expectedDraft==null?expectedCurrent:expectedDraft;
        if(baseId!=null){DraftTemplateGraphRevision base=requireRevision(projectId,baseId);requireSourcesCurrent(base);return JsonUtils.readMap(base.getGraphJson());}
        return map("kind","consense-template-graph-v1","graph",map("variables",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",1,"blank",true,"guidanceMode","recognized"),"sources",identities,"selectedDocuments",selected(identities));
    }
    private void restoreDraftCandidates(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){
        String evidence=evidenceRevision(projectId);
        Map<String,Object> trace=findCandidateTrace(projectId,item->Objects.equals(evidence,item.get("evidenceRevision")));
        if(!trace.isEmpty()) {
            Map<String,Object> found=asMap(trace.get("candidatesByKey"));for(Map<String,Object> field:extractionFields(graph)){
                String key=id(field);if(!Objects.equals(DraftReviewGraph.fieldHash(field),asMap(trace.get("fieldDefinitions")).get(key)))continue;
                DraftVariable variable=variable(projectId,key);List<CandidateVO> latest=JsonUtils.readList(JsonUtils.write(list(found.get(key))),CandidateVO.class);
                for(CandidateVO candidate:latest){candidate.setGraphRevisionId(revision.getId());candidate.setFieldDefinitionHash(DraftReviewGraph.fieldHash(field));}
                if((Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited()))&&!candidateIdentity(DraftAdoption.candidates(variable)).equals(candidateIdentity(latest)))variable.setReviewRequired(true);
                String candidateJson=JsonUtils.write(latest);
                if(!Objects.equals(variable.getCandidatesJson(),candidateJson)){variable.setCandidatesJson(candidateJson);variables.save(variable);}
                if(!"contractTitle".equals(key)){DraftGraphInputBinding binding=binding(projectId,key);if(!Objects.equals(binding.getCandidateRevisionId(),revision.getId())){binding.setCandidateRevisionId(revision.getId());bindings.save(binding);}}
            }
        }
    }
    private VariableVO candidateVariableVO(DraftTemplateGraphRevision revision,Map<String,Object> field,List<CandidateVO> candidates){Set<String> values=new LinkedHashSet<>();for(CandidateVO candidate:candidates)values.add(candidate.getValue());return JsonUtils.mapper().convertValue(map("key",id(field),"scope","INPUT","label",LocalizedText.of(string(field.get("nameZh")),string(field.get("nameZh")),string(field.get("nameEn"))),"kind",asMap(field.get("type")).get("kind"),"value",values.size()==1?values.iterator().next():"","confirmed",false,"manuallyEdited",false,"reviewRequired",false,"candidates",candidates,"graphRevisionId",revision.getId(),"fieldDefinitionHash",DraftReviewGraph.fieldHash(field),"adoptionState",values.size()>1?"conflict":values.isEmpty()?"missing":"suggested"),VariableVO.class);}
    private String candidateIdentity(List<CandidateVO> candidates){List<Object> identity=new ArrayList<>();for(CandidateVO candidate:candidates){Map<String,Object> metadata=JsonUtils.mapper().convertValue(candidate,Map.class);identity.add(Arrays.asList(candidate.getValue(),candidate.getSourceDocumentId(),candidate.getSourceHash(),metadata.get("originalSha256"),candidate.getSourceQuote()));}return JsonUtils.write(identity);}
    private Map<String,Object> pinGraph(String projectId,Map<String,Object> proposed) {
        Map<String,Object> graph=copy(proposed);if(graph.containsKey("state")&&!graph.containsKey("graph"))graph.put("graph",graph.remove("state"));
        if(!graph.containsKey("graph"))graph=map("kind","consense-template-graph-v1","graph",graph);
        if(JsonUtils.write(graph).getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new BizException(4007,"GRAPH_TOO_LARGE");
        List<Map<String,Object>> actual=templateIdentities(projectId);Map<String,Map<String,Object>> byDoc=new HashMap<>();
        for(Map<String,Object> source:actual){byDoc.put(String.valueOf(source.get("docId")),source);byDoc.put(String.valueOf(source.get("id")),source);}
        for(Object raw:list(graph.get("sources"))){Map<String,Object> supplied=asMap(raw),current=byDoc.get(String.valueOf(supplied.get("id")));if(current==null)throw conflict("Graph source is no longer available.");for(String key:Arrays.asList("sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(!Objects.equals(current.get(key),supplied.get(key)))throw conflict("Graph source binding differs from current original or analysis.");}
        graph.put("kind","consense-template-graph-v1");graph.put("sources",actual);if(!(graph.get("selectedDocuments") instanceof Map))graph.put("selectedDocuments",selected(actual));
        for(Map.Entry<String,Object> entry:asMap(graph.get("selectedDocuments")).entrySet()){Map<String,Object> chosen=byDoc.get(String.valueOf(entry.getValue()));if(chosen==null||!entry.getKey().equals(chosen.get("fileKey")))throw conflict("Selected document does not belong to this template family.");entry.setValue(chosen.get("id"));}
        Map<Long,DraftReviewSources.BoundSource> boundSources=new HashMap<>();Set<String> targetIds=new HashSet<>();for(Object raw:list(state(graph).get("variables"))) {
            Map<String,Object> node=asMap(raw);String nodeId=id(node);if(nodeId.length()>64)throw new BizException(4007,"GRAPH_INPUT_ID_TOO_LONG");
            if(Arrays.asList("targetOverrides","targetEdits","_evidenceSnapshot").contains(nodeId))throw new BizException(4007,"GRAPH_RESERVED_INPUT_ID");
            List<Object> docIds=new ArrayList<>();for(Object docId:list(node.get("docIds"))){Map<String,Object> identity=byDoc.get(String.valueOf(docId));docIds.add(identity==null?docId:identity.get("id"));}node.put("docIds",docIds);
            for(Object evidence:list(node.get("evidence"))){Map<String,Object> item=asMap(evidence),identity=byDoc.get(String.valueOf(item.get("docId")));if(identity!=null)item.put("docId",identity.get("id"));}
            for(String collection:Arrays.asList("sourceAnchors","editTargets"))for(Object anchorRaw:list(node.get(collection))) {
                Map<String,Object> anchor=asMap(anchorRaw);Map<String,Object> identity=byDoc.get(String.valueOf(anchor.get("docId")));
                if(identity==null){anchor.put("nativeBinding",map("status","missing","reason","Source document is unavailable."));continue;}
                SourceDocument source=requireSource(projectId,((Number)identity.get("sourceDocumentId")).longValue());
                DraftReviewSources.BoundSource binding=boundSources.computeIfAbsent(source.getId(),ignored->sourceReader.bindSource(source));
                anchor.put("docId",identity.get("id"));
                if("editTargets".equals(collection)&&JsonUtils.isBlankText(string(anchor.get("id"))))anchor.put("id",UUID.randomUUID().toString());
                if("editTargets".equals(collection)&&!targetIds.add(string(anchor.get("id"))))throw new BizException(4007,"GRAPH_DUPLICATE_TARGET_ID");
                anchor.put("nativeBinding",JsonUtils.mapper().convertValue(binding.bind(JsonUtils.mapper().valueToTree(anchor)),Map.class));
                if("sourceAnchors".equals(collection))anchor.put("sourceObservationBinding",JsonUtils.mapper().convertValue(binding.bindObservation(JsonUtils.mapper().valueToTree(anchor)),Map.class));
            }
        }
        return graph;
    }
    private List<Object> archives(String projectId,Map<String,Object> graph){List<Object> result=new ArrayList<>();for(Object raw:list(graph.get("sources"))){Map<String,Object> source=asMap(raw);result.add(sourceReader.archive(requireSource(projectId,((Number)source.get("sourceDocumentId")).longValue())));}return result;}
    private DraftTemplateGraphRevision create(String projectId,String parent,String status,Map<String,Object> graph,List<?> archives){String json=JsonUtils.write(graph);if(json.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new BizException(4007,"GRAPH_TOO_LARGE");return revisions.save(new DraftTemplateGraphRevision(UUID.randomUUID().toString(),projectId,parent,status,DraftAdoption.hash(json),json,JsonUtils.write(archives),Instant.now()));}
    private Map<String,Object> revisionVO(DraftTemplateGraphRevision revision,boolean content){return revisionVO(revision,content,new HashMap<>());}
    private Map<String,Object> revisionVO(DraftTemplateGraphRevision revision,boolean content,Map<Long,JsonNode> checkedSources){Map<String,Object> result=map("id",revision.getId(),"parentRevisionId",revision.getParentRevisionId(),"status",revision.getStatus(),"graphSha256",revision.getGraphSha256(),"createdAt",revision.getCreatedAt(),"confirmedAt",revision.getConfirmedAt(),"sourceStale",stale(revision,checkedSources));if(content)result.put("graph",JsonUtils.readMap(revision.getGraphJson()));return result;}
    public boolean traceStale(String projectId,Map<String,Object> payload) {
        try{DraftTemplateGraphHead head=head(projectId);String id=string(payload.get("graphRevisionId"));if(id==null||!Objects.equals(id,head.getCurrentRevisionId())&&!Objects.equals(id,head.getDraftRevisionId()))return true;return stale(requireRevision(projectId,id))||!Objects.equals(evidenceRevision(projectId),payload.get("evidenceRevision"));}catch(RuntimeException changed){return true;}
    }
    private boolean stale(DraftTemplateGraphRevision revision){return stale(revision,new HashMap<>());}
    private boolean stale(DraftTemplateGraphRevision revision,Map<Long,JsonNode> checkedSources){try{requireSourcesCurrent(revision,checkedSources);return false;}catch(RuntimeException changed){return true;}}
    private void requireSourcesCurrent(DraftTemplateGraphRevision revision){requireSourcesCurrent(revision,new HashMap<>());}
    private void requireSourcesCurrent(DraftTemplateGraphRevision revision,Map<Long,JsonNode> checkedSources){for(JsonNode archived:JsonUtils.parse(revision.getSourcesJson())){long id=archived.path("sourceDocumentId").asLong(-1);JsonNode actual=checkedSources.computeIfAbsent(id,sourceId->sourceReader.archive(requireSource(revision.getProjectId(),sourceId)));for(String key:Arrays.asList("sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(!archived.path(key).equals(actual.path(key)))throw conflict("Template source or analysis changed; confirm a new source-bound graph.");sourceReader.archivedBytes(archived);}}
    private DraftTemplateGraphRevision workingRevision(String projectId){
        projects.require(projectId);DraftTemplateGraphHead head=head(projectId);
        DraftTemplateGraphRevision current=head.getCurrentRevisionId()==null?null:requireRevision(projectId,head.getCurrentRevisionId());
        if(head.getDraftRevisionId()!=null){
            Set<String> visited=new HashSet<>();DraftTemplateGraphRevision ancestor=current;
            while(ancestor!=null){
                if(!visited.add(ancestor.getId()))throw conflict("Graph revision history contains a cycle.");
                if(Objects.equals(ancestor.getId(),head.getDraftRevisionId()))return current;
                ancestor=ancestor.getParentRevisionId()==null?null:requireRevision(projectId,ancestor.getParentRevisionId());
            }
            return requireRevision(projectId,head.getDraftRevisionId());
        }
        if(current==null)throw new BizException(4011,"GRAPH_NOT_CONFIRMED");return current;
    }
    private DraftTemplateGraphRevision requireRevision(String projectId,String revisionId){return revisions.findByIdAndProjectId(revisionId,projectId).orElseThrow(()->new BizException(4011,"GRAPH_REVISION_NOT_FOUND"));}
    private SourceDocument requireSource(String projectId,Long sourceId){return sources.findById(sourceId).filter(source->projectId.equals(source.getProjectId())).orElseThrow(()->new BizException(4011,"SOURCE_NOT_FOUND"));}
    private DraftTemplateGraphHead head(String projectId){return heads.findById(projectId).orElseGet(()->{DraftTemplateGraphHead result=new DraftTemplateGraphHead();result.setProjectId(projectId);return result;});}
    private DraftTemplateGraphHead lockedHead(String projectId){lock(projectId);DraftTemplateGraphHead head=head(projectId);if(entityManager.contains(head))entityManager.refresh(head);return head;}
    private void lock(String projectId){if(entityManager.find(Project.class,projectId,LockModeType.PESSIMISTIC_WRITE)==null)throw new BizException(4004,"Project not found.");}
    private void expected(String actual,String expected){if(!Objects.equals(actual,expected))throw conflict("The displayed graph revision changed; reload before saving or adopting.");}
    private static BizException conflict(String message){return new BizException(4090,"GRAPH_REVISION_CONFLICT: "+message);}
    private List<SourceDocument> templateSources(String projectId){
        List<SourceDocument> result=new ArrayList<>();for(SourceDocument source:sources.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_STANDARD_TEMPLATE))if(!DraftTemplateRouting.isUnadopted(source))result.add(source);return result;
    }
    private List<Map<String,Object>> templateIdentities(String projectId){return JsonUtils.mapper().convertValue(sourceReader.sources(templateSources(projectId)),List.class);}
    private List<JsonNode> templateReadings(String projectId){List<JsonNode> result=new ArrayList<>();for(SourceDocument source:templateSources(projectId))result.add(sourceReader.reading(source));return result;}
    private Map<String,Object> selected(List<Map<String,Object>> identities){Map<String,Object> result=new LinkedHashMap<>();for(Map<String,Object> source:identities){String key=string(source.get("fileKey"));if(Arrays.asList("NTT","SCT","SCC").contains(key)&&(!result.containsKey(key)||"docx".equals(source.get("format"))))result.put(key,source.get("id"));}return result;}
    private void requireReviewedSource(String projectId,Map<String,Object> graph,Map<String,Object> node){
        boolean supported=false;for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=asMap(raw);for(Object identityRaw:list(graph.get("sources"))){Map<String,Object> identity=asMap(identityRaw);if(!Objects.equals(string(anchor.get("docId")),string(identity.get("docId")))&&!Objects.equals(string(anchor.get("docId")),string(identity.get("id"))))continue;
                SourceDocument source=requireSource(projectId,((Number)identity.get("sourceDocumentId")).longValue());String quote=string(anchor.get("text"));
                if("bound".equals(asMap(anchor.get("nativeBinding")).get("status"))||"bound".equals(asMap(anchor.get("sourceObservationBinding")).get("status"))||quote!=null&&!quote.trim().isEmpty()&&source.getTextContent()!=null&&source.getTextContent().contains(quote))supported=true;
            }}if(!supported)throw new BizException(4007,"GRAPH_SOURCE_PASSAGE_REQUIRED: "+node.get("id"));
    }
    private List<Map<String,Object>> extractionFields(Map<String,Object> graph){List<Map<String,Object>> result=new ArrayList<>(DraftReviewGraph.fields(graph));result.add(identityField());return result;}
    private Map<String,Object> identityField(){DraftBlueprint.InputSpec spec=DraftBlueprint.find("contractTitle");List<Object> children=new ArrayList<>();
        for(String key:Arrays.asList("number","title"))children.add(map("id",key,"type",map("kind","text"),"role",map("kind","input"),"optional",false,"children",Collections.emptyList()));
        return map("id","contractTitle","nameZh",spec.labelZhHans,"nameEn",spec.labelEn,"type",map("kind","record"),"role",map("kind","input"),"children",children,"optional",false);
    }
    private String normalizeCandidate(Map<String,Object> field,String value){return "contractTitle".equals(id(field))?DraftInputRules.normalize(DraftBlueprint.find("contractTitle"),value):DraftReviewGraph.normalize(field,value);}
    private void ensureIdentity(String projectId){if(variables.findByProjectIdAndVarKey(projectId,"contractTitle").isPresent())return;DraftBlueprint.InputSpec spec=DraftBlueprint.find("contractTitle");DraftVariable variable=new DraftVariable();variable.setProjectId(projectId);variable.setVarKey(spec.key);variable.setScope("INPUT");variable.setKind(spec.kind);variable.setValueText("");variable.setCandidatesJson("[]");variable.setConfirmed(false);variable.setManuallyEdited(false);variable.setReviewRequired(false);variable.setSortOrder(-1);variable.setLabelZhHans(spec.labelZhHans);variable.setLabelZhHant(spec.labelZhHant);variable.setLabelEn(spec.labelEn);variable.setUpdatedAt(Instant.now());variables.save(variable);}
    private void ensureInputs(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){ensureIdentity(projectId);int order=0;for(Map<String,Object> field:allFields(graph)){String key=id(field),definition=DraftReviewGraph.fieldHash(field);DraftVariable variable=variables.findByProjectIdAndVarKey(projectId,key).orElse(null);DraftGraphInputBinding binding=bindings.findById(bindingId(projectId,key)).orElse(null);
            if(variable!=null&&binding!=null&&Objects.equals(binding.getDefinitionSha256(),definition)
                &&Objects.equals(variable.getScope(),"INPUT")&&Objects.equals(variable.getKind(),String.valueOf(asMap(field.get("type")).get("kind")))
                &&Objects.equals(variable.getLabelZhHans(),string(field.get("nameZh")))&&Objects.equals(variable.getLabelZhHant(),string(field.get("nameZh")))
                &&Objects.equals(variable.getLabelEn(),string(field.get("nameEn")))&&Objects.equals(variable.getSortOrder(),order)
                &&Objects.equals(variable.getOptionsJson(),JsonUtils.write(field.get("options")))){order++;continue;}
            if(variable==null){variable=new DraftVariable();variable.setProjectId(projectId);variable.setVarKey(key);variable.setValueText("");variable.setCandidatesJson("[]");}
            if(binding==null){binding=new DraftGraphInputBinding();binding.setId(bindingId(projectId,key));binding.setProjectId(projectId);binding.setVarKey(key);if(!JsonUtils.isBlankText(variable.getValueText()))variable.setReviewRequired(true);}
            if(!Objects.equals(binding.getDefinitionSha256(),definition)&&!JsonUtils.isBlankText(variable.getValueText()))variable.setReviewRequired(true);
            binding.setDefinitionSha256(definition);bindings.save(binding);variable.setScope("INPUT");variable.setKind(String.valueOf(asMap(field.get("type")).get("kind")));variable.setLabelZhHans(string(field.get("nameZh")));variable.setLabelZhHant(string(field.get("nameZh")));variable.setLabelEn(string(field.get("nameEn")));variable.setSortOrder(order++);variable.setOptionsJson(JsonUtils.write(field.get("options")));variable.setUpdatedAt(Instant.now());variables.save(variable);
        }}
    private VariableVO variableVO(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> field){return variableVO(projectId,revision,field,stale(revision));}
    private VariableVO variableVO(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> field,boolean sourceStale){
        DraftVariable variable=variable(projectId,id(field));boolean adopted=Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getManuallyEdited());
        boolean review=Boolean.TRUE.equals(variable.getReviewRequired())||sourceStale;
        DraftGraphInputBinding binding=bindings.findById(bindingId(projectId,id(field))).orElse(null);
        if(adopted&&(binding==null||!Objects.equals(binding.getAdoptedDefinitionSha256(),DraftReviewGraph.fieldHash(field))||"targetOverrides".equals(id(field))&&!Objects.equals(binding.getAdoptedRevisionId(),revision.getId())))review=true;
        if(adopted&&"targetOverrides".equals(id(field))&&overridesNeedReview(revision,JsonUtils.readMap(revision.getGraphJson()),variable.getValueText()))review=true;
        if(adopted)try{for(CandidateVO candidate:DraftAdoption.adoptedSources(variable))verifyCandidateSource(projectId,candidate);}catch(RuntimeException changed){review=true;}
        List<CandidateVO> candidates=DraftAdoption.candidates(variable);Set<String> values=new HashSet<>();for(CandidateVO candidate:candidates)values.add(candidate.getValue());
        String adoptionState=review?"needs_review":adopted?"adopted":JsonUtils.isBlankText(variable.getValueText())?(values.size()>1?"conflict":candidates.isEmpty()?"missing":"suggested"):"suggested";
        Map<String,Object> result=map("key",id(field),"scope","INPUT","group","reviewed","label",LocalizedText.of(variable.getLabelZhHans(),variable.getLabelZhHant(),variable.getLabelEn()),"kind",variable.getKind(),"value",variable.getValueText(),"confirmed",variable.getConfirmed(),"source",variable.getSourceRef(),"note",variable.getNoteText(),"manuallyEdited",variable.getManuallyEdited(),"reviewRequired",review,"candidates",candidates,"graphRevisionId",revision.getId(),"fieldDefinitionHash",DraftReviewGraph.fieldHash(field),"adoptionState",adoptionState);
        return JsonUtils.mapper().convertValue(result,VariableVO.class);
    }
    private DraftVariable variable(String projectId,String key){return variables.findByProjectIdAndVarKey(projectId,key).orElseThrow(()->new BizException(4006,"Input not found."));}
    private DraftGraphInputBinding binding(String projectId,String key){return bindings.findById(bindingId(projectId,key)).orElseThrow(()->new BizException(4006,"Input binding not found."));}
    private String bindingId(String projectId,String key){return DraftAdoption.hash(projectId+"\n"+key);}
    private List<Map<String,Object>> allFields(Map<String,Object> graph){List<Map<String,Object>> result=new ArrayList<>(DraftReviewGraph.fields(graph));Map<String,Object> override=overrideField(graph);if(override!=null)result.add(override);return result;}
    private Map<String,Object> overrideField(Map<String,Object> graph){boolean targets=false;for(Object node:list(state(graph).get("variables")))if(!list(asMap(node).get("editTargets")).isEmpty())targets=true;if(!targets)return null;return map("id","targetOverrides","nameZh","精确目标改文","nameEn","Exact target amendments","type",map("kind","text"),"role",map("kind","system"),"questionEn",hash(graph));}
    private Map<String,Map<String,Object>> currentTargets(Map<String,Object> graph){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Object raw:list(DraftReviewGraph.plan(graph,Collections.emptyMap()).get("actions"))){Map<String,Object> target=asMap(raw);result.put(string(target.get("id")),target);}return result;}
    private Map<String,Object> targetRevision(DraftTemplateGraphRevision revision,Map<String,Object> target){return map("graphRevisionId",revision.getId(),"sourceSha256",asMap(target.get("nativeBinding")).get("sourceSha256"));}
    private Map<String,Object> targetDecision(Map<String,Object> entry){Map<String,Object> result=new LinkedHashMap<>(entry);result.remove("sourceRevision");return result;}
    private String normalizeOverrides(DraftTemplateGraphRevision revision,Map<String,Object> graph,String raw,String previous){
        if(JsonUtils.isBlankText(raw))return "[]";JsonNode json=JsonUtils.parse(raw);if(!json.isArray())throw new BizException(4007,"TARGET_OVERRIDES_ARRAY_REQUIRED");
        Map<String,Map<String,Object>> allowed=currentTargets(graph),old=new HashMap<>();if(!JsonUtils.isBlankText(previous))for(Object item:list(DraftBusinessRules.parse(previous))){Map<String,Object> entry=asMap(item);old.put(string(entry.get("actionId")),entry);}
        List<Object> result=new ArrayList<>();Set<String> seen=new HashSet<>();for(JsonNode entry:json){if(!entry.isObject())throw new BizException(4007,"TARGET_OVERRIDE_OBJECT_REQUIRED");Map<String,Object> override=JsonUtils.mapper().convertValue(entry,Map.class);String id=string(override.get("actionId")),action=string(override.get("action"));Map<String,Object> target=allowed.get(id),existing=old.get(id);
            if(!seen.add(id)||!Arrays.asList("retain","amend","delete","not_used").contains(action))throw new BizException(4007,"TARGET_OVERRIDE_NOT_ALLOWED: "+id);
            if("amend".equals(action)&&(!(override.get("value") instanceof String)||JsonUtils.isBlankText((String)override.get("value"))))throw new BizException(4007,"TARGET_OVERRIDE_TEXT_REQUIRED: "+id);
            // Unchanged entries retain their evidence; omitting the binding explicitly re-adopts only this entry.
            if(override.get("sourceRevision")!=null&&existing!=null&&targetDecision(existing).equals(targetDecision(override))){if(!Objects.equals(override.get("sourceRevision"),existing.get("sourceRevision")))throw conflict("Target source binding is server-managed; re-adopt this target explicitly.");override.put("sourceRevision",existing.get("sourceRevision"));}
            else{
                if(target==null)throw new BizException(4007,"TARGET_OVERRIDE_NOT_ALLOWED: "+id);
                if(!"bound".equals(asMap(target.get("nativeBinding")).get("status")))throw new BizException(4012,"TARGET_OVERRIDE_SOURCE_UNBOUND: "+id);
                if("amend".equals(action)&&!canAmendTarget(target))throw new BizException(4012,"GRAPH_REPLACEMENT_PLACEMENT_REQUIRED: "+id);
                if(Arrays.asList("delete","not_used").contains(action)&&DraftReviewGraph.hasInsertionPoint(target))throw new BizException(4012,"GRAPH_INSERTION_ACTION_MISMATCH: "+id);
                override.put("sourceRevision",targetRevision(revision,target));
            }
            result.add(override);
        }return JsonUtils.write(result);
    }
    private boolean canAmendTarget(Map<String,Object> action){Map<String,Object> target=asMap(action.get("target"));if(target.isEmpty())target=action;return "insert".equals(target.get("action"))?DraftReviewGraph.hasInsertionPoint(target):DraftReviewGraph.hasReplacementPlacement(target);}
    private boolean overridesNeedReview(DraftTemplateGraphRevision revision,Map<String,Object> graph,String raw){try{Map<String,Map<String,Object>> allowed=currentTargets(graph);for(Object item:list(DraftBusinessRules.parse(raw))){Map<String,Object> entry=asMap(item),target=allowed.get(string(entry.get("actionId")));if(target==null||!targetRevision(revision,target).equals(entry.get("sourceRevision"))||"amend".equals(entry.get("action"))&&!canAmendTarget(target)||Arrays.asList("delete","not_used").contains(entry.get("action"))&&DraftReviewGraph.hasInsertionPoint(target))return true;}return false;}catch(RuntimeException bad){return true;}}
    private void applyOverrides(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph,Map<String,Object> plan,Map<String,String> patches){
        if(overrideField(graph)==null)return;DraftVariable variable=variables.findByProjectIdAndVarKey(projectId,"targetOverrides").orElse(null);String previous=variable==null?null:variable.getValueText(),raw=previous;
        boolean preview=patches!=null&&patches.containsKey("targetOverrides");if(preview){raw=normalizeOverrides(revision,graph,patches.get("targetOverrides"),previous);asMap(plan.get("inputValues")).put("targetOverrides",DraftBusinessRules.parse(raw));}else if(variable==null||!Boolean.TRUE.equals(variable.getConfirmed()))return;
        if(JsonUtils.isBlankText(raw)||"[]".equals(raw))return;
        try{Set<String> overridden=new HashSet<>();Map<String,Map<String,Object>> allowed=currentTargets(graph);for(Object item:list(DraftBusinessRules.parse(raw))){Map<String,Object> override=asMap(item);String id=string(override.get("actionId"));Map<String,Object> current=allowed.get(id);
                if(current==null||!targetRevision(revision,current).equals(override.get("sourceRevision"))){addIssue(plan,"target_override_review:"+id,"Saved target amendment requires review against this graph revision: "+id);for(Object actionRaw:list(plan.get("actions"))){Map<String,Object> action=asMap(actionRaw);if(Objects.equals(action.get("id"),id))action.put("adoptionState","needs_review");}continue;}
                if("amend".equals(override.get("action"))&&!canAmendTarget(current)||Arrays.asList("delete","not_used").contains(override.get("action"))&&DraftReviewGraph.hasInsertionPoint(current)){addIssue(plan,"target_override_placement:"+id,"Choose the exact insertion point or replacement span for this saved treatment: "+id);for(Object actionRaw:list(plan.get("actions"))){Map<String,Object> action=asMap(actionRaw);if(Objects.equals(action.get("id"),id)){action.put("action","pending");action.put("adoptionState","needs_review");}}continue;}
                for(Object actionRaw:list(plan.get("actions"))){Map<String,Object> action=asMap(actionRaw);if(!Objects.equals(action.get("id"),id))continue;action.put("action","amend".equals(override.get("action"))&&DraftReviewGraph.hasInsertionPoint(current)?"insert":override.get("action"));action.put("value","amend".equals(override.get("action"))?override.get("value"):"not_used".equals(override.get("action"))?"Not used":"delete".equals(override.get("action"))?"":null);action.put("adoptionOrigin","manual_target");overridden.add(id);}}
            List<Object> unresolved=new ArrayList<>();for(Object issue:list(plan.get("unresolved"))){Map<String,Object> value=asMap(issue);boolean resolved=false;for(Object key:list(value.get("inputKeys")))if(overridden.contains(String.valueOf(key)))resolved=true;if(!resolved)unresolved.add(issue);}plan.put("unresolved",unresolved);
        }catch(RuntimeException invalid){addIssue(plan,"target_override_invalid",invalid.getMessage());}
    }
    private Map<String,Object> visibleValues(String projectId,Map<String,Object> graph){Map<String,Object> result=new LinkedHashMap<>();for(Map<String,Object> field:allFields(graph))variables.findByProjectIdAndVarKey(projectId,id(field)).ifPresent(value->result.put(id(field),"targetOverrides".equals(id(field))?DraftBusinessRules.parse(value.getValueText()):decode(field,value.getValueText())));return result;}
    private Map<String,Object> effective(String projectId,DraftTemplateGraphRevision revision,Map<String,Object> graph){Map<String,Object> result=new LinkedHashMap<>();if(stale(revision))return result;for(Map<String,Object> field:DraftReviewGraph.fields(graph)){String key=id(field);DraftVariable value=variables.findByProjectIdAndVarKey(projectId,key).orElse(null);DraftGraphInputBinding binding=bindings.findById(bindingId(projectId,key)).orElse(null);
            if(value==null||binding==null||Boolean.TRUE.equals(value.getReviewRequired())||!Boolean.TRUE.equals(value.getConfirmed())||!Objects.equals(binding.getAdoptedDefinitionSha256(),DraftReviewGraph.fieldHash(field))||(JsonUtils.isBlankText(value.getValueText())&&!"text".equals(asMap(field.get("type")).get("kind"))))continue;
            try{for(CandidateVO source:DraftAdoption.adoptedSources(value))verifyCandidateSource(projectId,source);result.put(key,decode(field,DraftReviewGraph.normalize(field,value.getValueText())));}catch(RuntimeException invalid){/* Retain historical values; invalid source or type never becomes effective. */}
        }return result;}
    private void verifyCandidateSource(String projectId,CandidateVO candidate){SourceDocument source=requireSource(projectId,candidate.getSourceDocumentId());if(!SourceDocument.CATEGORY_PROJECT_INPUT.equals(source.getCategory())||!Objects.equals(candidate.getSourceHash(),DraftAdoption.sourceHash(source)))throw conflict("Candidate source changed.");Map<String,Object> metadata=JsonUtils.mapper().convertValue(candidate,Map.class);JsonNode archive=sourceReader.archive(source);if(metadata.get("originalSha256")==null||!Objects.equals(metadata.get("originalSha256"),archive.path("sourceSha256").asText()))throw conflict("Candidate original bytes changed or were not bound.");if(JsonUtils.isBlankText(candidate.getSourceQuote())||source.getTextContent()==null||!source.getTextContent().contains(candidate.getSourceQuote()))throw conflict("Candidate quotation no longer exists.");}
    private List<SourceDocument> correspondence(String projectId){return sources.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_PROJECT_INPUT);}
    private String evidenceRevision(String projectId){List<Object> identities=new ArrayList<>();for(SourceDocument source:correspondence(projectId)){JsonNode archived=sourceReader.archive(source);identities.add(Arrays.asList(source.getId(),archived.path("sourceSha256").asText(),DraftAdoption.sourceHash(source)));}return hash(identities);}
    private Map<String,Object> field(Map<String,Object> graph,String key){Map<String,Object> field=optionalField(graph,key);if(field==null)throw new BizException(4007,"Unknown reviewed drafting input: "+key);return field;}
    private Map<String,Object> optionalField(Map<String,Object> graph,String key){if("contractTitle".equals(key))return identityField();for(Map<String,Object> field:DraftReviewGraph.fields(graph))if(id(field).equals(key))return field;return null;}
    private static Object decode(Map<String,Object> field,String value){String kind=string(asMap(field.get("type")).get("kind"));if("text".equals(kind))return value;if(JsonUtils.isBlankText(value))return null;return Arrays.asList("date","choice").contains(kind)?value:DraftBusinessRules.parse(value);}
    private static String id(Map<String,Object> field){return String.valueOf(field.get("id"));}
    private static String string(Object value){return value==null?null:String.valueOf(value);}
    private static String hash(Object value){return DraftAdoption.hash(JsonUtils.write(value));}
    private static Map<String,Object> copy(Map<String,Object> value){return new LinkedHashMap<>(JsonUtils.readMap(JsonUtils.write(value)));}
    private static Map<String,Object> state(Map<String,Object> graph){return asMap(graph.get("graph"));}
    @SuppressWarnings("unchecked") private static Map<String,Object> asMap(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List?(List<Object>)value:Collections.emptyList();}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}
    private static void addIssue(Map<String,Object> plan,String kind,String message){List<Object> issues=new ArrayList<>(list(plan.get("unresolved")));issues.add(map("id",kind,"kind",kind,"message",LocalizedText.same(message)));plan.put("unresolved",issues);}
}
